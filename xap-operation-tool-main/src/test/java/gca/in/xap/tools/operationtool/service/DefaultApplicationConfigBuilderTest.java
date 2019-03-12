package gca.in.xap.tools.operationtool.service;

import lombok.extern.slf4j.Slf4j;
import org.junit.Ignore;
import org.junit.Test;
import org.openspaces.admin.application.config.ApplicationConfig;

import java.io.File;

@Slf4j
@Ignore
public class DefaultApplicationConfigBuilderTest {

	@Test
	public void should_build_application_config() {
		final PropertiesMergeBuilder propertiesMergeBuilder = new PropertiesMergeBuilder();

		final DefaultApplicationConfigBuilder appDeployBuilder;

		File archiveFileOrDirectory = new File(".");
		File deploymentDescriptorsDirectory = new File("src/test/resources/deploymentdescriptors-sample01");

		appDeployBuilder = new DefaultApplicationConfigBuilder()
				.withApplicationArchiveFileOrDirectory(archiveFileOrDirectory)
				.withSharedProperties(propertiesMergeBuilder.getMergedProperties())
				.withDeploymentDescriptorsDirectory(deploymentDescriptorsDirectory)
		;
		log.info("appDeployBuilder = {}", appDeployBuilder);

		ApplicationConfig applicationConfig = appDeployBuilder.create();
		log.info("applicationConfig = {}", applicationConfig);
	}

}