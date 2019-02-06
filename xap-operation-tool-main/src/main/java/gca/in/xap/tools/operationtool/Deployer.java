package gca.in.xap.tools.operationtool;

import gca.in.xap.tools.operationtool.service.ApplicationConfigBuilder;
import gca.in.xap.tools.operationtool.service.UserDetailsConfigFactory;
import gca.in.xap.tools.operationtool.service.XapService;
import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.openspaces.admin.application.config.ApplicationConfig;
import org.openspaces.admin.pu.config.UserDetailsConfig;

import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.TimeoutException;

public class Deployer {

	private final UserDetailsConfigFactory userDetailsConfigFactory = new UserDetailsConfigFactory();

	public void doDeploy(
			String archiveFilename,
			boolean wholeMode,
			boolean restartEmptyContainers,
			ApplicationArguments applicationArguments) throws TimeoutException {
		UserDetailsConfig userDetails = userDetailsConfigFactory.createFromUrlEncodedValue(
				applicationArguments.username,
				applicationArguments.password
		);

		XapService xapService = new XapService.Builder()
				.locators(applicationArguments.locators)
				//.groups(applicationArguments.groups)
				.timeout(applicationArguments.timeoutDuration)
				.userDetails(userDetails)
				.create();

		final File archiveFileOrDirectory = new File(archiveFilename);

		ApplicationConfigBuilder appDeployBuilder = new ApplicationConfigBuilder()
				.withApplicationArchiveFileOrDirectory(archiveFileOrDirectory)
				.withUserDetailsConfig(userDetails);

		if (applicationArguments.commandLineArgs.length > 1) {
			appDeployBuilder.addContextProperties(Paths.get(applicationArguments.commandLineArgs[1]));
		}

		ApplicationConfig applicationConfig = appDeployBuilder.create();

		if (archiveFileOrDirectory.isFile()) {
			File outputDirectory = new File(".");
			unzip(archiveFileOrDirectory, outputDirectory);
		}

		xapService.printReportOnContainersAndProcessingUnits();

		if (wholeMode) {
			xapService.undeployIfExists(applicationConfig.getName());
			xapService.printReportOnContainersAndProcessingUnits();
		}

		if (restartEmptyContainers) {
			xapService.restartEmptyContainers();
		}

		if (wholeMode) {
			xapService.deployWhole(applicationConfig, applicationArguments.timeoutDuration);
		} else {
			xapService.deployProcessingUnits(applicationConfig, applicationArguments.timeoutDuration, restartEmptyContainers);
		}

		xapService.printReportOnContainersAndProcessingUnits();
	}

	public static void unzip(File archiveFile, File destinationDirectory) {
		try {
			ZipFile zipFile = new ZipFile(archiveFile);
			zipFile.extractAll(destinationDirectory.getPath());
		} catch (ZipException e) {
			throw new RuntimeException(e);
		}
	}

}
