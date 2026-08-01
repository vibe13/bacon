package org.jboss.bacon.experimental.impl.projectfinder;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.jboss.bacon.experimental.impl.config.BuildConfigGeneratorConfig;
import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.bacon.common.exception.FatalException;
import org.jboss.pnc.bacon.pnc.common.ClientCreator;
import org.jboss.pnc.client.EnvironmentClient;
import org.jboss.pnc.client.RemoteCollection;
import org.jboss.pnc.client.RemoteResourceException;
import org.jboss.pnc.dto.Environment;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnvironmentResolver {

    private static final int DEFAULT_MAVEN_MAJOR = 3;

    private final Map<String, Environment> environments = new HashMap<>();
    private final BuildConfigGeneratorConfig config;

    public EnvironmentResolver(BuildConfigGeneratorConfig config) {
        this.config = config;
        try {
            EnvironmentClient environmentClient = new ClientCreator<>(EnvironmentClient::new).newClient();
            RemoteCollection<Environment> all = environmentClient.getAll(Optional.empty(), Optional.empty());
            for (Environment env : all) {
                environments.put(env.getId(), env);
            }
            validateDefaultEnvironment(config.getDefaultValues().getEnvironmentName());
        } catch (RemoteResourceException e) {
            throw new FatalException("Failed to load PNC environment list.", e);
        }
    }

    EnvironmentResolver(Map<String, Environment> environments, BuildConfigGeneratorConfig config) {
        this.config = config;
        this.environments.putAll(environments);
    }

    private void validateDefaultEnvironment(String defaultEnv) {
        Environment byName = null;
        for (Environment env : environments.values()) {
            if (env.getName().equals(defaultEnv)) {
                if (!env.isDeprecated()) {
                    return;
                }
                byName = env;
            }
        }
        if (byName == null) {
            throw new FatalException(
                    "Could not find environment \"" + defaultEnv
                            + "\" in PNC, update default environment value in config.");
        }
        Environment replacement = resolve(byName);
        String suggestion = replacement.isDeprecated()
                ? " (deprecation path did not lead to an active environment)"
                : " Suggested replacement: \"" + replacement.getName() + "\"";
        throw new FatalException(
                "Default environment \"" + defaultEnv
                        + "\" is deprecated, update default environment value in config." + suggestion);
    }

    public Environment resolve(Environment env) {
        if (!env.isDeprecated()) {
            return env;
        }
        String replacementID = env.getAttributes().get("DEPRECATION_REPLACEMENT");// TODO: replace with constant from
                                                                                  // 2.5
        if (replacementID == null) {
            log.error(
                    "Environment " + env.getName() + " #" + env.getId()
                            + " is deprecated, but does not have a DEPRECATION_REPLACEMENT provided.");// TODO: replace
                                                                                                                                                                     // with constant
                                                                                                                                                                     // from 2.5
            return env;
        }
        Environment replacement = environments.get(replacementID);
        if (replacement == null) {
            log.error(
                    "Environment " + env.getName() + " #" + env.getId()
                            + " is deprecated, but DEPRECATION_REPLACEMENT points to invalid Environment #"
                            + replacementID + ".");// TODO: replace with constant from 2.5
            return env;
        }
        return resolve(replacement);
    }

    public Environment selectEnvironment(ProjectBuildInfo buildInfo) {
        List<Environment> candidates = environments.values()
                .stream()
                .filter(env -> !env.isDeprecated())
                .filter(env -> !env.isHidden())
                .filter(env -> matchesJdk(env, buildInfo.getJdkVersion()))
                .filter(env -> matchesBuildType(env, buildInfo.getBuildType()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            log.warn(
                    "No environment matches detected build info (JDK={}, type={}). Using default environment.",
                    buildInfo.getJdkVersion(),
                    buildInfo.getBuildType());
            return findByName(config.getDefaultValues().getEnvironmentName());
        }

        Environment selected = selectBestEnvironment(candidates, buildInfo);
        log.info(
                "Selected environment '{}' for build info (JDK={}, type={}, toolVersion={}, constraint={})",
                selected.getName(),
                buildInfo.getJdkVersion(),
                buildInfo.getBuildType(),
                buildInfo.getBuildToolVersion(),
                buildInfo.getBuildToolVersionConstraint());
        return selected;
    }

    private Environment selectBestEnvironment(List<Environment> candidates, ProjectBuildInfo buildInfo) {
        String attrKey = buildInfo.getBuildType() == BuildType.GRADLE ? "GRADLE" : "MAVEN";
        String requestedVersion = buildInfo.getBuildToolVersion();
        BuildToolVersionConstraint constraint = buildInfo.getBuildToolVersionConstraint();

        if (constraint == BuildToolVersionConstraint.REQUIRED_RANGE && requestedVersion != null) {
            return selectHighestSatisfyingRange(candidates, attrKey, requestedVersion);
        }
        if (requestedVersion != null) {
            return selectPreferredVersion(candidates, attrKey, requestedVersion);
        }
        if (buildInfo.getBuildType() == BuildType.MVN) {
            return selectHighestMavenVersion(candidates);
        }
        return candidates.stream().min(simpleEnvironmentComparator()).orElseThrow();
    }

    private Environment selectHighestSatisfyingRange(
            List<Environment> candidates,
            String attrKey,
            String requestedRange) {
        String normalizedRange = normalizeRequiredRange(requestedRange);
        try {
            VersionRange range = VersionRange.createFromVersionSpec(normalizedRange);
            List<Environment> satisfying = candidates.stream()
                    .filter(env -> range.containsVersion(new DefaultArtifactVersion(toolVersion(env, attrKey))))
                    .collect(Collectors.toList());
            if (!satisfying.isEmpty()) {
                List<Environment> compatible = "MAVEN".equals(attrKey)
                        ? preferDefaultMavenMajor(satisfying)
                        : satisfying;
                return selectHighestVersion(compatible, attrKey);
            }
            log.warn(
                    "No environment satisfies required {} version range {}. Selecting the highest available compatible environment.",
                    attrKey,
                    normalizedRange);
        } catch (InvalidVersionSpecificationException e) {
            log.warn(
                    "Could not parse required {} version range '{}'. Selecting the highest available compatible environment.",
                    attrKey,
                    requestedRange);
        }
        return "MAVEN".equals(attrKey)
                ? selectHighestMavenVersion(candidates)
                : selectHighestVersion(candidates, attrKey);
    }

    private String normalizeRequiredRange(String requestedRange) {
        String trimmed = requestedRange.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("(")) {
            return trimmed;
        }
        return "[" + trimmed + ",)";
    }

    private Environment selectPreferredVersion(
            List<Environment> candidates,
            String attrKey,
            String requestedVersion) {
        int[] requested = parseVersion(requestedVersion);
        List<Environment> sameMajor = filterByMajor(candidates, attrKey, requested[0]);
        List<Environment> eligible = sameMajor.isEmpty() ? candidates : sameMajor;
        return eligible.stream()
                .min(
                        Comparator.comparingInt((Environment env) -> toolVersionRank(env, attrKey, requested))
                                .thenComparingInt(env -> toolVersionDistance(env, attrKey, requested))
                                .thenComparing(simpleEnvironmentComparator()))
                .orElseThrow();
    }

    private Environment selectHighestMavenVersion(List<Environment> candidates) {
        return selectHighestVersion(preferDefaultMavenMajor(candidates), "MAVEN");
    }

    private List<Environment> preferDefaultMavenMajor(List<Environment> candidates) {
        Integer defaultMajor = defaultMavenMajor();
        if (defaultMajor == null) {
            return candidates;
        }
        List<Environment> sameMajor = filterByMajor(candidates, "MAVEN", defaultMajor);
        return sameMajor.isEmpty() ? candidates : sameMajor;
    }

    private Integer defaultMavenMajor() {
        String defaultName = config.getDefaultValues().getEnvironmentName();
        for (Environment environment : environments.values()) {
            if (environment.getName().equals(defaultName)) {
                String version = environment.getAttributes().get("MAVEN");
                return version == null ? DEFAULT_MAVEN_MAJOR : parseVersion(version)[0];
            }
        }
        return DEFAULT_MAVEN_MAJOR;
    }

    private List<Environment> filterByMajor(List<Environment> candidates, String attrKey, int major) {
        return candidates.stream()
                .filter(env -> parseVersion(toolVersion(env, attrKey))[0] == major)
                .collect(Collectors.toList());
    }

    private Environment selectHighestVersion(List<Environment> candidates, String attrKey) {
        return candidates.stream()
                .min(
                        Comparator.comparing(
                                (Environment env) -> new ComparableVersion(toolVersion(env, attrKey)),
                                Comparator.reverseOrder())
                                .thenComparing(simpleEnvironmentComparator()))
                .orElseThrow();
    }

    private Comparator<Environment> simpleEnvironmentComparator() {
        return Comparator.comparingInt((Environment env) -> env.getAttributes().size())
                .thenComparing(Environment::getName)
                .thenComparing(Environment::getId);
    }

    private String toolVersion(Environment env, String attrKey) {
        return env.getAttributes().getOrDefault(attrKey, "0");
    }

    private boolean matchesJdk(Environment env, JdkVersion jdkVersion) {
        String jdkAttr = env.getAttributes().get("JDK");
        if (jdkAttr == null) {
            return false;
        }
        JdkVersion envJdk = JdkVersion.fromVersionString(jdkAttr);
        return envJdk == jdkVersion;
    }

    private boolean matchesBuildType(Environment env, BuildType buildType) {
        Map<String, String> attrs = env.getAttributes();
        if (buildType == BuildType.GRADLE) {
            return attrs.containsKey("GRADLE");
        }
        return attrs.containsKey("MAVEN");
    }

    /**
     * Ranks environments into tiers: 0 = exact match, 1 = newer version (usable fallback), 2 = older or unparseable.
     */
    private int toolVersionRank(Environment env, String attrKey, int[] requested) {
        String version = env.getAttributes().get(attrKey);
        if (version == null) {
            return 2;
        }
        int[] parsed = parseVersion(version);
        int cmp = compareVersions(parsed, requested);
        if (cmp == 0) {
            return 0;
        }
        if (cmp > 0) {
            return 1;
        }
        return 2;
    }

    private int toolVersionDistance(Environment env, String attrKey, int[] requested) {
        String version = env.getAttributes().get(attrKey);
        if (version == null) {
            return Integer.MAX_VALUE;
        }
        int[] parsed = parseVersion(version);
        return versionDistance(parsed, requested);
    }

    static int versionDistance(int[] a, int[] b) {
        int d0 = Math.abs(a[0] - b[0]);
        int d1 = Math.abs(a[1] - b[1]);
        int d2 = Math.abs(a[2] - b[2]);
        return d0 * 1_000_000 + d1 * 1_000 + d2;
    }

    static int[] parseVersion(String version) {
        if (version == null) {
            return new int[] { 0, 0, 0 };
        }
        String[] parts = version.split("\\.");
        int[] result = new int[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try {
                result[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*", ""));
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private Environment findByName(String name) {
        for (Environment env : environments.values()) {
            if (env.getName().equals(name)) {
                return env;
            }
        }
        if (environments.isEmpty()) {
            log.warn("No environments available, returning default fallback environment");
            return defaultFallbackEnvironment();
        }
        log.error("Could not find environment '{}' by name, returning first available environment", name);
        return environments.values().iterator().next();
    }

    private static Environment defaultFallbackEnvironment() {
        return Environment.builder()
                .id("1593")
                .name("OpenJDK 1.8; RHEL 8; Mvn 3.9.5")
                .deprecated(false)
                .hidden(false)
                .attributes(Map.of("JDK", "1.8.0", "MAVEN", "3.9.5", "OS", "Linux"))
                .build();
    }
}
