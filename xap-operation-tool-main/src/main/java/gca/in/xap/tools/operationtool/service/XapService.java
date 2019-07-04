package gca.in.xap.tools.operationtool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import gca.in.xap.tools.operationtool.model.ComponentType;
import gca.in.xap.tools.operationtool.model.DumpReport;
import gca.in.xap.tools.operationtool.model.VirtualMachineDescription;
import gca.in.xap.tools.operationtool.predicates.container.IsEmptyContainerPredicate;
import gca.in.xap.tools.operationtool.service.deployer.ApplicationDeployer;
import gca.in.xap.tools.operationtool.service.deployer.ProcessingUnitDeployer;
import gca.in.xap.tools.operationtool.service.restartstrategy.RestartStrategy;
import gca.in.xap.tools.operationtool.service.restartstrategy.SequentialRestartStrategy;
import gca.in.xap.tools.operationtool.userinput.UserConfirmationService;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.openspaces.admin.Admin;
import org.openspaces.admin.application.Application;
import org.openspaces.admin.application.config.ApplicationConfig;
import org.openspaces.admin.dump.DumpResult;
import org.openspaces.admin.gsa.GridServiceAgent;
import org.openspaces.admin.gsc.GridServiceContainer;
import org.openspaces.admin.gsc.GridServiceContainers;
import org.openspaces.admin.gsm.GridServiceManager;
import org.openspaces.admin.gsm.GridServiceManagers;
import org.openspaces.admin.machine.Machine;
import org.openspaces.admin.pu.ProcessingUnit;
import org.openspaces.admin.pu.ProcessingUnitInstance;
import org.openspaces.admin.pu.ProcessingUnits;
import org.openspaces.admin.pu.config.ProcessingUnitConfig;
import org.openspaces.admin.pu.config.UserDetailsConfig;
import org.openspaces.admin.pu.topology.ProcessingUnitConfigHolder;
import org.openspaces.admin.vm.VirtualMachine;
import org.openspaces.admin.vm.VirtualMachineDetails;
import org.openspaces.admin.vm.VirtualMachines;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
public class XapService {

	private static void awaitTermination(final List<Future<?>> taskResults) {
		for (Future<?> taskResult : taskResults) {
			try {
				taskResult.get();
			} catch (InterruptedException e) {
				log.error("InterruptedException while waiting for task to complete", e);
			} catch (ExecutionException e) {
				log.error("ExecutionException while waiting for task to complete", e);
			}
		}
	}

	static void awaitDeployment(
			@NonNull final ApplicationConfig applicationConfig,
			@NonNull final Application dataApp,
			final long deploymentStartTime,
			@NonNull final Duration timeout) throws TimeoutException {
		long timeoutTime = deploymentStartTime + timeout.toMillis();

		final String applicationConfigName = applicationConfig.getName();
		log.info("Waiting for application {} to deploy ...", applicationConfigName);

		Set<String> deployedPuNames = new LinkedHashSet<>();

		final ProcessingUnits processingUnits = dataApp.getProcessingUnits();

		// get the pu names in the best order of deployment (regarding dependencies between them)
		final List<String> puNamesInOrderOfDeployment = ApplicationConfigHelper.getPuNamesInOrderOfDeployment(applicationConfig);

		for (String puName : puNamesInOrderOfDeployment) {
			ProcessingUnit pu = processingUnits.getProcessingUnit(puName);
			awaitDeployment(pu, deploymentStartTime, timeout, timeoutTime);
			deployedPuNames.add(puName);
		}

		long appDeploymentEndTime = System.currentTimeMillis();
		long appDeploymentDuration = appDeploymentEndTime - deploymentStartTime;

		log.info("Deployed PUs : {}", deployedPuNames);
		log.info("Application deployed in : {} ms", appDeploymentDuration);
	}

	static void awaitDeployment(@NonNull ProcessingUnit pu, long deploymentStartTime, @NonNull Duration timeout, long expectedMaximumEndDate) throws TimeoutException {
		String puName = pu.getName();
		final int plannedNumberOfInstances = pu.getPlannedNumberOfInstances();
		log.info("Waiting for PU {} to deploy {} instances ...", puName, plannedNumberOfInstances);

		long remainingDelayUntilTimeout = expectedMaximumEndDate - System.currentTimeMillis();
		if (remainingDelayUntilTimeout < 0L) {
			throw new TimeoutException("Application deployment timed out after " + timeout);
		}
		boolean finished = pu.waitFor(plannedNumberOfInstances, remainingDelayUntilTimeout, TimeUnit.MILLISECONDS);
		if (!finished) {
			throw new TimeoutException("Application deployment timed out after " + timeout);
		}
		final long deploymentEndTime = System.currentTimeMillis();
		final long deploymentDuration = deploymentEndTime - deploymentStartTime;

		final int currentInstancesCount = pu.getInstances().length;
		log.info("PU {} deployed successfully after {} ms, now has {} running instances", puName, deploymentDuration, currentInstancesCount);

	}

	private static long durationSince(long time) {
		return System.currentTimeMillis() - time;
	}

	private final DateTimeFormatter dumpsFileNamesDateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

	@Setter
	private Admin admin;

	/**
	 * the timeout of the operation (deployment, undeployment)
	 */
	@Setter
	private Duration operationTimeout = Duration.of(1, ChronoUnit.MINUTES);

	@Setter
	private UserDetailsConfig userDetails;

	@Setter
	private ExecutorService executorService;

	@Setter
	private UserConfirmationService userConfirmationService;

	@Setter
	private IdExtractor idExtractor;

	@Setter
	private PuRelocateService puRelocateService;

	@Setter
	private ProcessingUnitDeployer processingUnitDeployer;

	@Setter
	private ApplicationDeployer applicationDeployer;

	private final ObjectMapper objectMapper = new ObjectMapperFactory().createObjectMapper();

	public GridServiceContainer[] findContainers() {
		GridServiceContainers gridServiceContainers = admin.getGridServiceContainers();
		GridServiceContainer[] containers = gridServiceContainers.getContainers();
		// we want the GSCs to be sorted by Id, for readability and reproducibility
		Arrays.sort(containers, Comparator.comparing(GridServiceContainer::getId));
		return containers;
	}

	public GridServiceManager[] findManagers() {
		GridServiceManagers gridServiceManagers = admin.getGridServiceManagers();
		GridServiceManager[] managers = gridServiceManagers.getManagers();
		// we want the GSCs to be sorted by Id, for readability and reproducibility
		Arrays.sort(managers, Comparator.comparing(gsm -> gsm.getMachine().getHostName()));
		return managers;
	}

	public List<String> findManagersHostnames() {
		final GridServiceManager[] managers = findManagers();
		return Arrays.stream(managers).map(gsm -> gsm.getMachine().getHostName()).collect(Collectors.toList());
	}

	public Machine[] findAllMachines() {
		Machine[] machines = admin.getMachines().getMachines();
		Arrays.sort(machines, Comparator.comparing(Machine::getHostName));
		return machines;
	}

	public ProcessingUnit findProcessingUnitByName(String processingUnitName) {
		ProcessingUnit processingUnit = admin.getProcessingUnits().getProcessingUnit(processingUnitName);
		return processingUnit;
	}

	public List<String> findAllProcessingUnitsNames() {
		ProcessingUnit[] processingUnits = admin.getProcessingUnits().getProcessingUnits();
		List<String> result = Arrays.stream(processingUnits).map(processingUnit -> processingUnit.getName()).collect(Collectors.toList());
		return result;
	}

	public void printReportOnContainersAndProcessingUnits() {
		printReportOnContainersAndProcessingUnits(gsc -> true);
	}

	public void printReportOnContainersAndProcessingUnits(Predicate<GridServiceContainer> predicate) {
		GridServiceContainer[] containers = findContainers();
		containers = Arrays.stream(containers).filter(predicate).toArray(GridServiceContainer[]::new);
		final int gscCount = containers.length;
		final Collection<String> containersIds = idExtractor.extractIds(containers);
		log.info("Found {} matching running GSC instances : {}", gscCount, containersIds);
		for (GridServiceContainer gsc : containers) {
			String gscId = gsc.getId();
			ProcessingUnitInstance[] puInstances = gsc.getProcessingUnitInstances();
			final int puCount = puInstances.length;
			final Collection<String> puNames = idExtractor.extractProcessingUnitsNamesAndDescription(puInstances);
			log.info("GSC {} is running {} Processing Units : {}", gscId, puCount, puNames);
		}
	}

	public void printReportOnManagers() {
		final GridServiceManager[] managers = findManagers();
		final int gsmCount = managers.length;
		final Collection<String> managersIds = idExtractor.extractIds(managers);
		log.info("Found {} running GSM instances : {}", gsmCount, managersIds);
	}

	public void printReportOnVirtualMachines() {
		VirtualMachines virtualMachines = admin.getVirtualMachines();
		final int jvmCount = virtualMachines.getSize();
		log.info("Found {} JVMs", jvmCount);

		final List<VirtualMachineDescription> virtualMachineDescriptions = new ArrayList<>();


		for (VirtualMachine jvm : virtualMachines.getVirtualMachines()) {
			final VirtualMachineDetails details = jvm.getDetails();
			final String jvmDescription = details.getVmVendor() + " : " + details.getVmName() + " : " + details.getVmVersion();
			//
			VirtualMachineDescription vmDescription = new VirtualMachineDescription();
			vmDescription.setUid(jvm.getUid());
			vmDescription.setComponentType(getComponentType(jvm));
			vmDescription.setUptime(Duration.ofMillis(jvm.getStatistics().getUptime()));
			vmDescription.setHostName(jvm.getMachine().getHostName());
			vmDescription.setJvmDescription(jvmDescription);
			vmDescription.setHeapSizeInMBInit(Math.round(details.getMemoryHeapInitInMB()));
			vmDescription.setHeapSizeInMBMax(Math.round(details.getMemoryHeapMaxInMB()));
			virtualMachineDescriptions.add(vmDescription);
		}

		virtualMachineDescriptions.sort(new VirtualMachineDescriptionComparator());

		for (VirtualMachineDescription jvm : virtualMachineDescriptions) {
			log.info("{} : {} : running on {} for {} : Heap [{} MB, {} MB] : {}",
					jvm.getComponentType(),
					jvm.getUid().substring(0, 7) + "...",
					jvm.getHostName(),
					padRight(jvm.getUptime(), 17),
					padLeft(jvm.getHeapSizeInMBInit(), 5),
					padLeft(jvm.getHeapSizeInMBMax(), 5),
					jvm.getJvmDescription());
		}
	}

	public static String padLeft(Object value, int length) {
		return String.format("%" + length + "s", value);
	}

	public static String padRight(Object value, int length) {
		return String.format("%-" + length + "s", value);
	}

	public ComponentType getComponentType(VirtualMachine jvm) {
		GridServiceContainer gridServiceContainer = jvm.getGridServiceContainer();
		if (gridServiceContainer != null) {
			return ComponentType.GSC;
		}
		GridServiceManager gridServiceManager = jvm.getGridServiceManager();
		if (gridServiceManager != null) {
			return ComponentType.GSM;
		}
		GridServiceAgent gridServiceAgent = jvm.getGridServiceAgent();
		if (gridServiceAgent != null) {
			return ComponentType.GSA;
		}
		return null;
	}

	/**
	 * you may want to restart containers after a PU has been undeployed, in order to make sure no unreleased resources remains.
	 */
	public void restartEmptyContainers() {
		log.warn("Will restart all empty GSC instances ... (GSC with no PU running)");
		restartContainers(new IsEmptyContainerPredicate(), new SequentialRestartStrategy<>(Duration.ZERO));
	}

	public void restartContainers(@NonNull Predicate<GridServiceContainer> predicate, @NonNull RestartStrategy<GridServiceContainer> restartStrategy) {
		GridServiceContainer[] containers = findContainers();
		containers = Arrays.stream(containers).filter(predicate).toArray(GridServiceContainer[]::new);
		final int gscCount = containers.length;
		final Collection<String> containersIds = idExtractor.extractIds(containers);
		log.info("Found {} matching GSC instances : {}", gscCount, containersIds);

		log.warn("Will restart {} GSC instances : {}", gscCount, containersIds);
		userConfirmationService.askConfirmationAndWait();
		restartStrategy.perform(containers, new RestartStrategy.ContainerItemVisitor());
		log.info("Triggered restart of GSC instances : {}", containersIds);
	}

	public void restartManagers(@NonNull Predicate<GridServiceManager> predicate, @NonNull RestartStrategy<GridServiceManager> restartStrategy) {
		GridServiceManager[] managers = findManagers();
		managers = Arrays.stream(managers).filter(predicate).toArray(GridServiceManager[]::new);
		final int gsmCount = managers.length;
		final Collection<String> managersIds = idExtractor.extractIds(managers);
		log.info("Found {} matching GSM instances : {}", gsmCount, managersIds);

		log.warn("Will restart {] GSM instances : {}", gsmCount, managersIds);
		userConfirmationService.askConfirmationAndWait();
		restartStrategy.perform(managers, new RestartStrategy.ManagerItemVisitor());
		log.info("Triggered restart of GSM instances : {}", managersIds);
	}

	public void shutdownAgents(@NonNull Predicate<GridServiceAgent> predicate, @NonNull RestartStrategy<GridServiceAgent> restartStrategy) {
		GridServiceAgent[] agents = admin.getGridServiceAgents().getAgents();
		agents = Arrays.stream(agents).filter(predicate).toArray(GridServiceAgent[]::new);
		final int gsaCount = agents.length;
		final Collection<String> agentIds = idExtractor.extractIds(agents);
		log.info("Found {} matching GSA instances : {}", gsaCount, agentIds);

		log.warn("Will shutdown {} GSA instances : {}", gsaCount, agentIds);
		userConfirmationService.askConfirmationAndWait();
		restartStrategy.perform(agents, new RestartStrategy.AgentItemVisitor());
		log.info("Triggered shutdown of GSA instances : {}", agentIds);
	}

	public void triggerGarbageCollectorOnEachGsc() {
		final GridServiceContainer[] containers = findContainers();
		final int gscCount = containers.length;
		final Collection<String> containersIds = idExtractor.extractIds(containers);
		log.info("Found {} running GSC instances : {}", gscCount, containersIds);

		final List<Future<?>> taskResults = new ArrayList<>();
		// this can be done in parallel to perform quicker when there are a lot of containers
		Arrays.stream(containers).forEach(gsc -> {
			Future<?> taskResult = executorService.submit(() -> {
				final String gscId = gsc.getId();
				try {
					log.info("Triggering GC on GSC {} ...", gscId);
					gsc.getVirtualMachine().runGc();
				} catch (RuntimeException e) {
					log.error("Failure while triggering Garbage Collector on GSC {}", gscId, e);
				}
			});
			taskResults.add(taskResult);
		});
		awaitTermination(taskResults);
		log.info("Triggered GC on GSC instances : {}", containersIds);
	}

	public void generateHeapDumpOnEachGsc() throws IOException {
		String[] dumpTypes = {"heap"};
		final File outputDirectory = new File("dumps/heap");
		generateDumpOnEachGsc(outputDirectory, dumpTypes);
	}

	public void generateThreadDumpOnEachGsc() throws IOException {
		String[] dumpTypes = {"thread"};
		final File outputDirectory = new File("dumps/thread");
		generateDumpOnEachGsc(outputDirectory, dumpTypes);
	}

	private void generateDumpOnEachGsc(final File outputDirectory, final String[] dumpTypes) throws IOException {
		final GridServiceContainer[] containers = findContainers();
		final int gscCount = containers.length;
		final Collection<String> containersIds = idExtractor.extractIds(containers);
		log.info("Found {} running GSC instances : {}", gscCount, containersIds);

		boolean outputDirectoryCreated = outputDirectory.mkdirs();
		log.debug("outputDirectoryCreated = {]", outputDirectoryCreated);
		if (!outputDirectory.canWrite()) {
			throw new IOException("Cannot write to directory " + outputDirectory + " (" + outputDirectory.getAbsolutePath() + "). Please execute the command from a working directory where you have write access.");
		}

		final List<Future<?>> taskResults = new ArrayList<>();
		// this can be done in parallel to perform quicker when there are a lot of containers
		Arrays.stream(containers).forEach(gsc -> {
			Future<?> taskResult = executorService.submit(() -> {
				final String gscId = gsc.getId();
				try {
					generateDump(gsc, outputDirectory, dumpTypes);
				} catch (RuntimeException | IOException e) {
					log.error("Failure while generating a Heap Dump on GSC {}", gscId, e);
				}
			});
			taskResults.add(taskResult);
		});
		awaitTermination(taskResults);
		log.info("Triggered Heap Dump on GSC instances : {}", containersIds);
	}

	private void generateDump(@NonNull GridServiceContainer gsc, @NonNull final File outputDirectory, String[] dumpTypes) throws IOException {
		final String gscId = gsc.getId();

		final Machine machine = gsc.getMachine();

		long pid = gsc.getVirtualMachine().getDetails().getPid();

		ProcessingUnitInstance[] processingUnitInstances = gsc.getProcessingUnitInstances();
		Collection<String> processingUnitsNames = idExtractor.extractProcessingUnitsNames(processingUnitInstances);

		final ZonedDateTime time = ZonedDateTime.now();
		final String dumpFileName = "dump-" + gscId + "-" + time.format(dumpsFileNamesDateTimeFormatter) + ".zip";
		final String reportFileName = "dump-" + gscId + "-" + time.format(dumpsFileNamesDateTimeFormatter) + ".json";
		final File dumpFile = new File(outputDirectory, dumpFileName);
		final File reportFile = new File(outputDirectory, reportFileName);
		//

		final List<String> dumpsTypesList = Arrays.asList(dumpTypes);

		DumpReport dumpReport = new DumpReport();
		dumpReport.setDumpsTypes(dumpsTypesList);
		dumpReport.setGscId(gscId);
		dumpReport.setPid(pid);
		dumpReport.setStartTime(time);
		dumpReport.setDumpFileName(dumpFileName);
		dumpReport.setProcessingUnitsNames(new ArrayList<>(processingUnitsNames));
		dumpReport.setHostName(machine.getHostName());
		dumpReport.setHostAddress(machine.getHostAddress());
		objectMapper.writeValue(reportFile, dumpReport);

		//
		log.info("Asking GSC {} for a dump of {} ...", gscId, dumpsTypesList);
		final DumpResult dumpResult = gsc.generateDump("Generating a dump with XAP operation tool for " + dumpsTypesList, null, dumpTypes);
		log.info("Downloading dump from gsc {} to file {} ...", gscId, dumpFile.getAbsolutePath());
		dumpResult.download(dumpFile, null);
		log.info("Wrote file {} : size = {} bytes", dumpFile.getAbsolutePath(), dumpFile.length());
	}


	public void deployWhole(ApplicationConfig applicationConfig, Duration timeout) throws TimeoutException {
		log.info("Attempting deployment of application '{}' composed of : {} with a timeout of {}",
				applicationConfig.getName(),
				ApplicationConfigHelper.getPuNamesInOrderOfDeployment(applicationConfig),
				timeout
		);

		long deployRequestStartTime = System.currentTimeMillis();
		Application dataApp = applicationDeployer.deploy(applicationConfig, timeout.toMillis(), TimeUnit.MILLISECONDS);
		long deployRequestEndTime = System.currentTimeMillis();
		long deployRequestDuration = deployRequestEndTime - deployRequestStartTime;
		log.info("Requested deployment of application : duration = {} ms", deployRequestDuration);

		if (dataApp == null) {
			throw new DeploymentRequestException("Deployment request failed, GridServiceManagers returned null");
		}

		long deploymentStartTime = deployRequestEndTime;
		awaitDeployment(applicationConfig, dataApp, deploymentStartTime, operationTimeout);
	}

	public void deployProcessingUnits(
			ApplicationConfig applicationConfig,
			Predicate<String> processingUnitsPredicate,
			Duration timeout,
			boolean restartEmptyContainers
	) throws TimeoutException {
		log.info("Attempting deployment of application '{}' composed of : {} with a timeout of {}",
				applicationConfig.getName(),
				ApplicationConfigHelper.getPuNamesInOrderOfDeployment(applicationConfig),
				timeout
		);

		final long deploymentStartTime = System.currentTimeMillis();
		final long expectedMaximumEndDate = deploymentStartTime + timeout.toMillis();

		for (ProcessingUnitConfigHolder pu : applicationConfig.getProcessingUnits()) {
			final String puName = pu.getName();
			if (!processingUnitsPredicate.test(puName)) {
				log.info("Skipping Processing Unit {} as requested by user", puName);
			} else {
				doDeployProcessingUnit(pu, puName, timeout, expectedMaximumEndDate);
			}
		}

		long deployRequestEndTime = System.currentTimeMillis();
		long appDeploymentDuration = deployRequestEndTime - deploymentStartTime;

		log.info("Application deployed in: {} ms", appDeploymentDuration);
	}

	private void doDeployProcessingUnit(
			final ProcessingUnitConfigHolder pu,
			final String puName,
			final Duration timeout,
			final long expectedMaximumEndDate
	) throws TimeoutException {
		final ProcessingUnitConfig processingUnitConfig = pu.toProcessingUnitConfig();
		log.debug("puName = {}, processingUnitConfig = {}", puName, processingUnitConfig);

		undeployPu(puName);

		log.info("Deploying pu {} ...", puName);
		long puDeploymentStartTime = System.currentTimeMillis();

		ProcessingUnit processingUnit = processingUnitDeployer.deploy(puName, processingUnitConfig);
		awaitDeployment(processingUnit, puDeploymentStartTime, timeout, expectedMaximumEndDate);
	}

	private void undeployPu(String puName) {
		doWithProcessingUnit(puName, Duration.of(10, ChronoUnit.SECONDS), existingProcessingUnit -> {
			final int instancesCount = existingProcessingUnit.getInstances().length;
			log.info("Undeploying pu {} ... ({} instances are running on GSCs {})", puName, instancesCount, idExtractor.extractContainerIds(existingProcessingUnit));
			long startTime = System.currentTimeMillis();
			boolean undeployedSuccessful = existingProcessingUnit.undeployAndWait(1, TimeUnit.MINUTES);
			long endTime = System.currentTimeMillis();
			long duration = endTime - startTime;
			if (undeployedSuccessful) {
				log.info("Undeployed pu {} in {} ms", puName, duration);
			} else {
				log.warn("Timeout waiting for pu {} to undeploy after {} ms", puName, duration);
			}
		}, s -> {
			log.info("ProcessingUnit " + puName + " is not already deployed");
		});
	}

	public void undeploy(String applicationName) {
		log.info("Launch undeploy of {}, operationTimeout = {}", applicationName, operationTimeout);
		doWithApplication(
				applicationName,
				operationTimeout,
				application -> {
					undeploy(application);
				},
				appName -> {
					throw new IllegalStateException(new TimeoutException(
							"Application " + appName + " discovery timed-out. Check if it is deployed."));
				}
		);
	}

	public void undeployIfExists(String name) {
		log.info("Undeploying application {} (if it exists) ...", name);
		doWithApplication(
				name,
				Duration.of(5, ChronoUnit.SECONDS),
				app -> {
					undeploy(app);
				},
				appName -> {
					log.warn("Application {} was not found, could not be undeployed", name);
				});
	}

	public void undeploy(@NonNull Application application) {
		final String applicationName = application.getName();
		log.info("Undeploying application : {}", applicationName);
		application.undeployAndWait(operationTimeout.toMillis(), TimeUnit.MILLISECONDS);
		log.info("{} has been successfully undeployed.", applicationName);
	}

	public void undeployProcessingUnits(@NonNull Predicate<String> processingUnitsNamesPredicate) {
		List<String> allProcessingUnitsNames = this.findAllProcessingUnitsNames();
		allProcessingUnitsNames.stream().filter(processingUnitsNamesPredicate).forEach(puName -> {

			try {
				undeployPu(puName);
			} catch (RuntimeException e) {
				log.error("Failure while undeploying PU {}", puName, e);
			}

		});
	}

	public void doWithApplication(String name, Duration timeout, Consumer<Application> ifFound, Consumer<String> ifNotFound) {
		Application application = admin.getApplications().waitFor(name, timeout.toMillis(), TimeUnit.MILLISECONDS);
		if (application == null) {
			ifNotFound.accept(name);
		} else {
			ifFound.accept(application);
		}
	}

	public void doWithProcessingUnit(String name, Duration timeout, Consumer<ProcessingUnit> ifFound, Consumer<String> ifNotFound) {
		ProcessingUnit processingUnit = admin.getProcessingUnits().waitFor(name, timeout.toMillis(), TimeUnit.MILLISECONDS);
		if (processingUnit == null) {
			ifNotFound.accept(name);
		} else {
			ifFound.accept(processingUnit);
		}
	}

	public void setDefaultTimeout(Duration timeout) {
		log.info("Admin will use a default timeout of {} ms", timeout.toMillis());
		admin.setDefaultTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

}
