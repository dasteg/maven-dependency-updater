package org.jboss.set.mavendependencyupdater.projectparser;

import org.apache.commons.cli.*;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.execution.*;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Profile;
import org.apache.maven.model.profile.DefaultProfileActivationContext;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.SettingsUtils;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.codehaus.plexus.*;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectRef;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.core.ManipulationSession;
import org.commonjava.maven.ext.io.PomIO;
import org.jboss.logging.Logger;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Retrieves information about maven project dependencies.
 */
public class PmeDependencyCollector {

    private static final File DEFAULT_GLOBAL_SETTINGS_FILE =
            new File(System.getenv("M2_HOME"), "conf/settings.xml");

    private static final Logger LOG = Logger.getLogger(PmeDependencyCollector.class);

    private ManipulationSession session;

    private PomIO pomIO;

    private PlexusContainer container;

    private List<Project> projects;

    private Map<ProjectRef, Collection<ArtifactRef>> projectsDependencies = new HashMap<>();

    private ProjectRef rootProjectRef;

    /**
     * @deprecated
     */
    public static void main(String[] args) {
        Options options = new Options();
        options.addOption(Option.builder("f")
                .longOpt("file")
                .hasArgs()
                .numberOfArgs(1)
                .required()
                .desc("POM file")
                .build());
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            LOG.debug("Caught problem parsing ", e);

            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("...", options);
            return;
        }

        if (cmd.hasOption('f')) {
            File pomFile = new File(cmd.getOptionValue('f'));

            try {
                Map<ProjectRef, Collection<ArtifactRef>> projectsDeps =
                        new PmeDependencyCollector(pomFile).getAllProjectsDependencies();

                for (Map.Entry<ProjectRef, Collection<ArtifactRef>> entry: projectsDeps.entrySet()) {
                    System.out.println(entry.getKey().toString());
                    for (ArtifactRef ref : entry.getValue()) {
                        System.out.println("  " + ref.asProjectVersionRef().toString());
                    }
                }
            } catch (ManipulationException e) {
                LOG.error("Project evaluation failed", e);
            }
        }
    }

    public PmeDependencyCollector(File pomFile) throws ManipulationException {
        LOG.infof("Creating collector for project %s", pomFile);
        createSession(pomFile, null);
        projects = pomIO.parseProject(pomFile);

        Project rootProject = projects.stream().filter(Project::isInheritanceRoot).findFirst().get();
        rootProjectRef = toProjectRef(rootProject);

        collectProjectDependencies();
    }

    public Map<ProjectRef, Collection<ArtifactRef>> getAllProjectsDependencies() {
        // TODO: unmodifiable
        return projectsDependencies;
    }

    public Collection<ArtifactRef> getProjectDependencies(String groupId, String artifactId) {
        // TODO: unmodifiable
        return projectsDependencies.get(new SimpleProjectRef(groupId, artifactId));
    }

    public Collection<ArtifactRef> getRootProjectDependencies() {
        return projectsDependencies.get(rootProjectRef);
    }

    private void collectProjectDependencies() throws ManipulationException {
        for (Project project:  projects) {
            Collection<ArtifactRef> dependencies = new HashSet<>();
            projectsDependencies.put(toProjectRef(project), dependencies);

            collectDependencies(dependencies, project.getResolvedManagedDependencies(session));
            collectDependencies(dependencies, project.getResolvedDependencies(session));
        }
    }

    private void collectDependencies(Collection<ArtifactRef> collectTo, Map<ArtifactRef, Dependency> dependencies) {
        collectTo.addAll(dependencies.keySet().stream()
                .filter(ref -> !ref.getVersionString().contains("&"))
                .collect(Collectors.toList()));
    }

    private void createSession(File target, File settings) {
        try {
            final DefaultContainerConfiguration config = new DefaultContainerConfiguration();
            config.setClassPathScanning(PlexusConstants.SCANNING_ON);
            config.setComponentVisibility(PlexusConstants.GLOBAL_VISIBILITY);
            config.setName("PME-CLI");
            container = new DefaultPlexusContainer(config);

            pomIO = container.lookup(PomIO.class);
            session = container.lookup(ManipulationSession.class);
//            manipulationManager = container.lookup(ManipulationManager.class);

            final MavenExecutionRequest req = new DefaultMavenExecutionRequest().setSystemProperties(System.getProperties())
//                    .setUserProperties(userProps)
                    .setRemoteRepositories(Collections.emptyList());

            ArtifactRepository ar = null;
            if (settings == null) {
                // No, this is not a typo. If current default is null, supply new local and global.
                // This function passes in settings to make it easier to test.
                settings = new File(System.getProperty("user.home"), ".m2/settings.xml");

                ar = new MavenArtifactRepository();
                ar.setUrl("file://" + System.getProperty("user.home") + "/.m2/repository");
                req.setLocalRepository(ar);
            }

            req.setUserSettingsFile(settings);
            req.setGlobalSettingsFile(settings);

            MavenExecutionRequestPopulator executionRequestPopulator = container.lookup(MavenExecutionRequestPopulator.class);

            executionRequestPopulator.populateFromSettings(req, parseSettings(settings));
            executionRequestPopulator.populateDefaults(req);

            if (ar != null) {
                ar.setUrl("file://" + req.getLocalRepositoryPath());
            }

            /*if (userProps != null && userProps.containsKey("maven.repo.local")) {
                if (ar == null) {
                    ar = new MavenArtifactRepository();
                }
                ar.setUrl("file://" + userProps.getProperty("maven.repo.local"));
                req.setLocalRepository(ar);
            }*/

            final MavenSession mavenSession = new MavenSession(container, null, req, new DefaultMavenExecutionResult());

            mavenSession.getRequest().setPom(target);

            session.setMavenSession(mavenSession);
        } catch (ComponentLookupException | PlexusContainerException e) {
            throw new IllegalStateException("Caught problem instantiating PlexusContainer", e);
        } catch (SettingsBuildingException e) {
            throw new IllegalStateException("Caught problem parsing settings file ", e);
        } catch (MavenExecutionRequestPopulationException e) {
            throw new IllegalStateException("Caught problem populating maven request from settings file ", e);
        }
    }

    private Settings parseSettings(File settings) throws ComponentLookupException, SettingsBuildingException {
        DefaultSettingsBuildingRequest settingsRequest = new DefaultSettingsBuildingRequest();
        settingsRequest.setUserSettingsFile(settings);
        settingsRequest.setGlobalSettingsFile(DEFAULT_GLOBAL_SETTINGS_FILE);
        settingsRequest.setUserProperties(session.getUserProperties());
        settingsRequest.setSystemProperties(System.getProperties());

        SettingsBuilder settingsBuilder = container.lookup(SettingsBuilder.class);
        SettingsBuildingResult settingsResult = settingsBuilder.build(settingsRequest);
        Settings effectiveSettings = settingsResult.getEffectiveSettings();

        ProfileSelector profileSelector = container.lookup(ProfileSelector.class);
        ProfileActivationContext profileActivationContext =
                new DefaultProfileActivationContext().setActiveProfileIds(effectiveSettings.getActiveProfiles());
        List<Profile> modelProfiles = new ArrayList<>();
        for (org.apache.maven.settings.Profile profile : effectiveSettings.getProfiles()) {
            modelProfiles.add(SettingsUtils.convertFromSettingsProfile(profile));
        }
        List<Profile> activeModelProfiles =
                profileSelector.getActiveProfiles(modelProfiles, profileActivationContext,
                        modelProblemCollectorRequest -> {
                            // ignore
                        });
        List<String> activeProfiles = new ArrayList<>();
        for (org.apache.maven.model.Profile profile : activeModelProfiles) {
            activeProfiles.add(profile.getId());
        }
        effectiveSettings.setActiveProfiles(activeProfiles);

        return effectiveSettings;
    }

    private static ProjectRef toProjectRef(Project project) {
        return new SimpleProjectRef(project.getGroupId(), project.getArtifactId());
    }
}
