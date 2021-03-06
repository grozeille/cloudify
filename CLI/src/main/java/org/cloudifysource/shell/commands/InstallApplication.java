/*******************************************************************************
 * Copyright (c) 2011 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.cloudifysource.shell.commands;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.cloudifysource.dsl.Application;
import org.cloudifysource.dsl.Service;
import org.cloudifysource.dsl.internal.CloudifyConstants;
import org.cloudifysource.dsl.internal.ServiceReader;
import org.cloudifysource.dsl.internal.packaging.Packager;
import org.cloudifysource.dsl.internal.packaging.ZipUtils;
import org.cloudifysource.shell.Constants;
import org.cloudifysource.shell.GigaShellMain;
import org.cloudifysource.shell.ShellUtils;
import org.cloudifysource.shell.rest.RestLifecycleEventsLatch;
import org.fusesource.jansi.Ansi.Color;

/**
 * @author rafi, barakm, adaml
 * @since 2.0.0
 * 
 *        Installs an application, including it's contained services ordered according to their dependencies.
 * 
 *        Required arguments: application-file - The application recipe file path, folder or archive (zip/jar)
 * 
 *        Optional arguments: name - The name of the application timeout - The number of minutes to wait until the
 *        operation is completed (default: 10 minutes)
 * 
 *        Command syntax: install-application [-name name] [-timeout timeout] application-file
 */
@Command(scope = "cloudify", name = "install-application", description = "Installs an application. If you specify"
		+ " a folder path it will be packed and deployed. If you sepcify an application archive, the shell will deploy"
		+ " that file.")
public class InstallApplication extends AdminAwareCommand {

	private static final int DEFAULT_TIMEOUT_MINUTES = 10;

	@Argument(required = true, name = "application-file", description = "The application recipe file path, folder "
			+ "or archive")
	private File applicationFile;

	@Option(required = false, name = "-name", description = "The name of the application")
	private String applicationName = null;

	@Option(required = false, name = "-timeout", description = "The number of minutes to wait until the operation"
			+ " is done.")
	private int timeoutInMinutes = DEFAULT_TIMEOUT_MINUTES;

	@Option(required = false, name = "-cloudConfiguration",
			description = "File or directory containing configuration information to be used by the cloud driver "
					+ "for this application")
	private File cloudConfiguration;

	private static final String TIMEOUT_ERROR_MESSAGE = "Application installation timed out."
			+ " Configure the timeout using the -timeout flag.";

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected Object doExecute()
			throws Exception {
		if (!applicationFile.exists()) {
			throw new CLIStatusException("application_not_found", applicationFile.getAbsolutePath());
		}

		logger.info("Validating file " + applicationFile.getName());
		final Application application = ServiceReader.getApplicationFromFile(applicationFile).getApplication();

		if (StringUtils.isBlank(applicationName)) {
			applicationName = application.getName();
		}

		if (adminFacade.getApplicationsList().contains(applicationName)) {
			throw new CLIStatusException("application_already_deployed", application.getName());
		}

		final File cloudConfigurationZipFile = createCloudConfigurationZipFile();
		File zipFile;
		if (applicationFile.isFile()) {
			if (applicationFile.getName().endsWith(".zip") || applicationFile.getName().endsWith(".jar")) {
				zipFile = applicationFile;
			} else {
				throw new CLIStatusException("application_file_format_mismatch", applicationFile.getPath());
			}
		} else { // pack an application folder
			if (cloudConfigurationZipFile == null) {
				zipFile = Packager.packApplication(application, applicationFile);
			} else {
				zipFile =
						Packager.packApplication(application, applicationFile,
								new File[] { cloudConfigurationZipFile });
			}
		}

		// toString of string list (i.e. [service1, service2])
		logger.info("Uploading application " + applicationName);

		final Map<String, String> result =
				adminFacade.installApplication(zipFile, applicationName, getTimeoutInMinutes());

		final String serviceOrder = result.get(CloudifyConstants.SERVICE_ORDER);

		// If temp file was created, Delete it.
		if (!applicationFile.isFile()) {
			zipFile.delete();
		}

		if (serviceOrder.charAt(0) != '[' && serviceOrder.charAt(serviceOrder.length() - 1) != ']') {
			throw new IllegalStateException("Cannot parse service order response: " + serviceOrder);
		}
		printApplicationInfo(application);
		
		session.put(Constants.ACTIVE_APP, applicationName);
		GigaShellMain.getInstance().setCurrentApplicationName(applicationName);
		
		final String pollingID = result.get(CloudifyConstants.LIFECYCLE_EVENT_CONTAINER_ID);
		final RestLifecycleEventsLatch lifecycleEventsPollingLatch =
				this.adminFacade.getLifecycleEventsPollingLatch(pollingID, TIMEOUT_ERROR_MESSAGE);
		boolean isDone = false;
		boolean continuous = false;
		while (!isDone) {
			try {
				if (!continuous) {
					lifecycleEventsPollingLatch.waitForLifecycleEvents(getTimeoutInMinutes(), TimeUnit.MINUTES);
				} else {
					lifecycleEventsPollingLatch.continueWaitForLifecycleEvents(getTimeoutInMinutes(), TimeUnit.MINUTES);
				}
				isDone = true;
			} catch (final TimeoutException e) {
				if (!(Boolean) session.get(Constants.INTERACTIVE_MODE)) {
					throw e;
				}
				final boolean continueInstallation = promptWouldYouLikeToContinueQuestion();
				if (!continueInstallation) {
					throw new CLIStatusException(e, "application_installation_timed_out_on_client",
							applicationName);
				} else {
					continuous = true;
				}
			}
		}

		return this.getFormattedMessage("application_installed_succesfully", Color.GREEN, applicationName);
	}

	private File createCloudConfigurationZipFile()
			throws CLIStatusException, IOException {
		if (this.cloudConfiguration == null) {
			return null;
		}

		if (!this.cloudConfiguration.exists()) {
			throw new CLIStatusException("cloud_configuration_file_not_found",
					this.cloudConfiguration.getAbsolutePath());
		}

		// create a temp file in a temp directory
		final File tempDir = File.createTempFile("__Cloudify_Cloud_configuration", ".tmp");
		FileUtils.forceDelete(tempDir);
		tempDir.mkdirs();

		final File tempFile = new File(tempDir, CloudifyConstants.SERVICE_CLOUD_CONFIGURATION_FILE_NAME);

		// mark files for deletion on JVM exit
		tempFile.deleteOnExit();
		tempDir.deleteOnExit();

		if (this.cloudConfiguration.isDirectory()) {
			ZipUtils.zip(this.cloudConfiguration, tempFile);
		} else if (this.cloudConfiguration.isFile()) {
			ZipUtils.zipSingleFile(this.cloudConfiguration, tempFile);
		} else {
			throw new IOException(this.cloudConfiguration + " is neither a file nor a directory");
		}

		return tempFile;
	}

	private boolean promptWouldYouLikeToContinueQuestion()
			throws IOException {
		return ShellUtils.promptUser(session, "would_you_like_to_continue_application_installation",
				this.applicationName);
	}

	/**
	 * Prints Application data - the application name and it's services name, dependencies and number of instances.
	 * 
	 * @param application Application object to analyze
	 */
	private void printApplicationInfo(final Application application) {
		logger.info("Application [" + applicationName + "] with " + application.getServices().size() + " services");
		for (final Service service : application.getServices()) {
			if (service.getDependsOn().isEmpty()) {
				logger.info("Service [" + service.getName() + "] " + service.getNumInstances() + " planned instances");
			} else { // Service has dependencies
				logger.info("Service [" + service.getName() + "] depends on " + service.getDependsOn().toString()
						+ " " + service.getNumInstances() + " planned instances");
			}
		}
	}

	public File getCloudConfiguration() {
		return cloudConfiguration;
	}

	public void setCloudConfiguration(final File cloudConfiguration) {
		this.cloudConfiguration = cloudConfiguration;
	}

	public int getTimeoutInMinutes() {
		return timeoutInMinutes;
	}

	public void setTimeoutInMinutes(final int timeoutInMinutes) {
		this.timeoutInMinutes = timeoutInMinutes;
	}
}
