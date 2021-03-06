package gca.in.xap.tools.operationtool.service;

import gca.in.xap.tools.operationtool.XapClientDiscovery;
import gca.in.xap.tools.operationtool.service.deployer.ProcessingUnitDeployerType;
import org.openspaces.admin.pu.config.UserDetailsConfig;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class XapServiceFactoryBean implements FactoryBean<XapService> {

	@Autowired
	private UserDetailsConfig userDetailsConfig;

	@Autowired
	private XapClientDiscovery xapClientDiscovery;

	@Autowired
	private XapServiceBuilder xapServiceBuilder;

	@Override
	public XapService getObject() {
		return xapServiceBuilder
				.locators(xapClientDiscovery.getLocators())
				.groups(xapClientDiscovery.getGroups())
				.timeout(xapClientDiscovery.getTimeoutDuration())
				.userDetails(userDetailsConfig)
				.processingUnitDeployerType(ProcessingUnitDeployerType.REST_API)
				.create();
	}

	@Override
	public Class<XapService> getObjectType() {
		return XapService.class;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}
}
