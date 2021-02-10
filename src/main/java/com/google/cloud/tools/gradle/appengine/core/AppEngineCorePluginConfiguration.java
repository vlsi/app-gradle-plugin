/*
 * Copyright 2016 Google LLC. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.google.cloud.tools.gradle.appengine.core;

import com.google.cloud.tools.appengine.operations.cloudsdk.CloudSdkNotFoundException;
import com.google.cloud.tools.managedcloudsdk.BadCloudSdkVersionException;
import com.google.cloud.tools.managedcloudsdk.ManagedCloudSdk;
import com.google.cloud.tools.managedcloudsdk.UnsupportedOsException;
import com.google.cloud.tools.managedcloudsdk.components.SdkComponent;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.util.GradleVersion;

/**
 * Core plugin for App Engine, contains common tasks like deploy and show configuration Also
 * instantiates the "tools" extension to specify the cloud sdk path.
 */
public class AppEngineCorePluginConfiguration {

  public static final GradleVersion GRADLE_MIN_VERSION = GradleVersion.version("4.9");

  public static final String LOGIN_TASK_NAME = "appengineCloudSdkLogin";
  public static final String DEPLOY_TASK_NAME = "appengineDeploy";
  public static final String DEPLOY_CRON_TASK_NAME = "appengineDeployCron";
  public static final String DEPLOY_DISPATCH_TASK_NAME = "appengineDeployDispatch";
  public static final String DEPLOY_DOS_TASK_NAME = "appengineDeployDos";
  public static final String DEPLOY_INDEX_TASK_NAME = "appengineDeployIndex";
  public static final String DEPLOY_QUEUE_TASK_NAME = "appengineDeployQueue";
  public static final String DEPLOY_ALL_TASK_NAME = "appengineDeployAll";
  public static final String SHOW_CONFIG_TASK_NAME = "appengineShowConfiguration";
  public static final String DOWNLOAD_CLOUD_SDK_TASK_NAME = "downloadCloudSdk";
  public static final String CHECK_CLOUD_SDK_TASK_NAME = "checkCloudSdk";

  public static final String APPENGINE_EXTENSION = "appengine";

  private Project project;
  private DeployExtension deployExtension;
  private ToolsExtension toolsExtension;
  private CloudSdkOperations cloudSdkOperations;
  private ManagedCloudSdk managedCloudSdk;
  private boolean requiresAppEngineJava;
  private String taskGroup;

  /** Configure core tasks for appengine app.yaml and appengine-web.xml based project plugins. */
  public void configureCoreProperties(
      Project project,
      AppEngineCoreExtensionProperties appEngineCoreExtensionProperties,
      String taskGroup,
      boolean requiresAppEngineJava) {
    checkGradleVersion();

    this.project = project;
    this.taskGroup = taskGroup;
    toolsExtension = appEngineCoreExtensionProperties.getTools();
    deployExtension = appEngineCoreExtensionProperties.getDeploy();
    this.requiresAppEngineJava = requiresAppEngineJava;
    configureFactories();

    createDownloadCloudSdkTask();
    createCheckCloudSdkTask();
    createLoginTask();
    createDeployTask();
    createDeployCronTask();
    createDeployDispatchTask();
    createDeployDosTask();
    createDeployIndexTask();
    createDeployQueueTask();
    createDeployAllTask();
    createShowConfigurationTask();
  }

  private void configureFactories() {
    project.afterEvaluate(
        projectAfterEvaluated -> {
          try {
            if (toolsExtension.getCloudSdkHome() == null) {
              managedCloudSdk =
                  new ManagedCloudSdkFactory(toolsExtension.getCloudSdkVersion()).newManagedSdk();
              toolsExtension.setCloudSdkHome(managedCloudSdk.getSdkHome().toFile());
            }
          } catch (UnsupportedOsException ex) {
            throw new RuntimeException(ex.getMessage(), ex);
          } catch (BadCloudSdkVersionException ex) {
            throw new RuntimeException(
                "Failed to auto-configure Cloud Sdk at cloudSdkVersion = '"
                    + toolsExtension.getCloudSdkVersion()
                    + "': "
                    + ex.getMessage(),
                ex);
          }

          try {
            cloudSdkOperations =
                new CloudSdkOperations(
                    toolsExtension.getCloudSdkHome(),
                    toolsExtension.getServiceAccountKeyFile(),
                    toolsExtension.getVerbosity());
          } catch (CloudSdkNotFoundException ex) {
            // this should never happen, not found exception only occurs when auto-discovery fails,
            // but we don't use that mechanism anymore.
            throw new AssertionError("Failed when attempting to discover SDK: ", ex);
          }

          deployExtension.setDeployTargetResolver(new DeployTargetResolver(cloudSdkOperations));
        });
  }

  private void createDownloadCloudSdkTask() {
    TaskProvider<DownloadCloudSdkTask> downloadCloudSdk =
        project
            .getTasks()
            .register(
                DOWNLOAD_CLOUD_SDK_TASK_NAME,
                DownloadCloudSdkTask.class,
                downloadCloudSdkTask -> {
                  downloadCloudSdkTask.setGroup(taskGroup);
                  downloadCloudSdkTask.setDescription("Download the Cloud SDK");

                  // make sure we download our required components
                  if (requiresAppEngineJava) {
                    downloadCloudSdkTask.requiresComponent(SdkComponent.APP_ENGINE_JAVA);
                  }
                });

    project.afterEvaluate(
        project -> {
          if (downloadCloudSdk.isPresent() && managedCloudSdk != null) {
            downloadCloudSdk.get().setManagedCloudSdk(managedCloudSdk);
            if (project.getGradle().getStartParameter().isOffline()) {
              project.getLogger().debug("Skipping DownloadCloudSdk in --offline mode.");
              return;
            }
            project
                .getTasks()
                .matching(task -> task.getName().startsWith("appengine"))
                .configureEach(appEngineTask -> appEngineTask.dependsOn(downloadCloudSdk));
          }
        });
  }

  private void createCheckCloudSdkTask() {
    TaskProvider<CheckCloudSdkTask> checkCloudSdk =
        project
            .getTasks()
            .register(
                CHECK_CLOUD_SDK_TASK_NAME,
                CheckCloudSdkTask.class,
                checkCloudSdkTask -> {
                  checkCloudSdkTask.setGroup(taskGroup);
                  checkCloudSdkTask.setDescription("Validates the Cloud SDK");
                });

    project.afterEvaluate(
        project -> {
          if (checkCloudSdk.isPresent()
              && managedCloudSdk == null
              && toolsExtension.getCloudSdkVersion() != null) {
            checkCloudSdk.get().setVersion(toolsExtension.getCloudSdkVersion());
            checkCloudSdk.get().setCloudSdk(cloudSdkOperations.getCloudSdk());
            checkCloudSdk.get().requiresAppEngineJava(requiresAppEngineJava);
            project
                .getTasks()
                .matching(task -> task.getName().startsWith("appengine"))
                .forEach(appEngineTask -> appEngineTask.dependsOn(checkCloudSdk));
          }
        });
  }

  private void createLoginTask() {
    injectGcloud(LOGIN_TASK_NAME);
    TaskProvider<?> cloudSdkLogin =
        project
            .getTasks()
            .register(
                LOGIN_TASK_NAME,
                CloudSdkLoginTask.class,
                loginTask -> {
                  loginTask.setGroup(taskGroup);
                  loginTask.setDescription("Login and set the Cloud SDK common configuration user");
                });

    project.afterEvaluate(
        project -> {
          if (cloudSdkLogin.isPresent() && toolsExtension.getServiceAccountKeyFile() != null) {
            cloudSdkLogin
                .get()
                .doLast(
                    task ->
                        project
                            .getLogger()
                            .warn(
                                "WARNING: ServiceAccountKeyFile is configured and will be"
                                    + " used instead of Cloud SDK auth state"));
          }
        });
  }

  private void createDeployTask() {
    injectGcloud(DEPLOY_TASK_NAME);
    project
        .getTasks()
        .register(
            DEPLOY_TASK_NAME,
            DeployTask.class,
            deployTask -> {
              deployTask.setGroup(taskGroup);
              deployTask.setDescription("Deploy an App Engine application");
              // deployConfig is set in AppEngineStandardPlugin and AppEngineAppYamlPlugin
            });
  }

  private void createDeployCronTask() {
    injectGcloud(DEPLOY_CRON_TASK_NAME);
    TaskProvider<DeployCronTask> deployCron =
        project
            .getTasks()
            .register(
                DEPLOY_CRON_TASK_NAME,
                DeployCronTask.class,
                deployTask -> {
                  deployTask.setGroup(taskGroup);
                  deployTask.setDescription("Deploy Cron configuration");
                });

    project.afterEvaluate(
        project -> {
          if (deployCron.isPresent()) {
            deployCron.get().setDeployExtension(deployExtension);
          }
        });
  }

  private void createDeployDispatchTask() {
    injectGcloud(DEPLOY_DISPATCH_TASK_NAME);
    TaskProvider<DeployDispatchTask> deployDispatch =
        project
            .getTasks()
            .register(
                DEPLOY_DISPATCH_TASK_NAME,
                DeployDispatchTask.class,
                deployTask -> {
                  deployTask.setGroup(taskGroup);
                  deployTask.setDescription("Deploy Dispatch configuration");
                });

    project.afterEvaluate(
        project -> {
          if (deployDispatch.isPresent()) {
            deployDispatch.get().setDeployExtension(deployExtension);
          }
        });
  }

  private void createDeployDosTask() {
    injectGcloud(DEPLOY_DOS_TASK_NAME);
    TaskProvider<DeployDosTask> deployDos =
        project
            .getTasks()
            .register(
                DEPLOY_DOS_TASK_NAME,
                DeployDosTask.class,
                deployTask -> {
                  deployTask.setGroup(taskGroup);
                  deployTask.setDescription("Deploy Dos configuration");
                });

    project.afterEvaluate(
        project -> {
          if (deployDos.isPresent()) {
            deployDos.get().setDeployExtension(deployExtension);
          }
        });
  }

  private void createDeployIndexTask() {
    injectGcloud(DEPLOY_INDEX_TASK_NAME);
    TaskProvider<DeployIndexTask> deployIndex =
        project
            .getTasks()
            .register(
                DEPLOY_INDEX_TASK_NAME,
                DeployIndexTask.class,
                deployTask -> {
                  deployTask.setGroup(taskGroup);
                  deployTask.setDescription("Deploy Index configuration");
                });

    project.afterEvaluate(
        project -> {
          if (deployIndex.isPresent()) {
            deployIndex.get().setDeployExtension(deployExtension);
          }
        });
  }

  private void createDeployQueueTask() {
    injectGcloud(DEPLOY_QUEUE_TASK_NAME);
    TaskProvider<DeployQueueTask> deployQueue =
        project
            .getTasks()
            .register(
                DEPLOY_QUEUE_TASK_NAME,
                DeployQueueTask.class,
                deployTask -> {
                  deployTask.setGroup(taskGroup);
                  deployTask.setDescription("Deploy Queue configuration");
                });

    project.afterEvaluate(
        project -> {
          if (deployQueue.isPresent()) {
            deployQueue.get().setDeployExtension(deployExtension);
          }
        });
  }

  private void createDeployAllTask() {
    injectGcloud(DEPLOY_ALL_TASK_NAME);
    project
        .getTasks()
        .register(
            DEPLOY_ALL_TASK_NAME,
            DeployAllTask.class,
            deployAllTask -> {
              deployAllTask.setGroup(taskGroup);
              deployAllTask.setDescription(
                  "Deploy an App Engine application and all of its config files");

              // deployConfig is set in AppEngineStandardPlugin and AppEngineAppYamlPlugin
            });
  }

  private void createShowConfigurationTask() {
    project
        .getTasks()
        .register(
            SHOW_CONFIG_TASK_NAME,
            ShowConfigurationTask.class,
            showConfigurationTask -> {
              showConfigurationTask.setGroup(taskGroup);
              showConfigurationTask.setDescription("Show current App Engine plugin configuration");

              showConfigurationTask.setExtensionId(APPENGINE_EXTENSION);
            });
  }

  private void checkGradleVersion() {
    if (GRADLE_MIN_VERSION.compareTo(GradleVersion.current()) > 0) {
      throw new GradleException(
          "Detected "
              + GradleVersion.current()
              + ", but the appengine-gradle-plugin requires "
              + GRADLE_MIN_VERSION
              + " or higher.");
    }
  }

  private void injectGcloud(String taskName) {
    project.afterEvaluate(
        project -> {
          TaskProvider<GcloudTask> task =
              project.getTasks().withType(GcloudTask.class).named(taskName);
          if (task.isPresent()) {
            task.get().setGcloud(cloudSdkOperations.getGcloud());
          }
        });
  }
}
