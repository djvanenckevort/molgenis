package org.molgenis.data.mapper.controller;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.stubbing.OngoingStubbing;
import org.molgenis.auth.User;
import org.molgenis.data.AbstractMolgenisSpringTest;
import org.molgenis.data.DataService;
import org.molgenis.data.jobs.JobExecutor;
import org.molgenis.data.mapper.job.MappingJobExecution;
import org.molgenis.data.mapper.job.MappingJobExecutionFactory;
import org.molgenis.data.mapper.mapping.model.AttributeMapping;
import org.molgenis.data.mapper.mapping.model.EntityMapping;
import org.molgenis.data.mapper.mapping.model.MappingProject;
import org.molgenis.data.mapper.mapping.model.MappingTarget;
import org.molgenis.data.mapper.service.AlgorithmService;
import org.molgenis.data.mapper.service.MappingService;
import org.molgenis.data.meta.MetaDataService;
import org.molgenis.data.meta.model.*;
import org.molgenis.data.meta.model.Package;
import org.molgenis.data.semantic.Relation;
import org.molgenis.data.semanticsearch.service.OntologyTagService;
import org.molgenis.data.semanticsearch.service.SemanticSearchService;
import org.molgenis.ontology.core.model.OntologyTerm;
import org.molgenis.security.user.UserAccountService;
import org.molgenis.security.user.UserService;
import org.molgenis.ui.jobs.JobsController;
import org.molgenis.ui.menu.Menu;
import org.molgenis.ui.menu.MenuReaderService;
import org.molgenis.util.GsonConfig;
import org.molgenis.util.GsonHttpMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.Model;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.mockito.Mockito.*;
import static org.molgenis.data.mapper.controller.MappingServiceController.URI;
import static org.molgenis.data.meta.AttributeType.DATE;
import static org.molgenis.data.meta.AttributeType.INT;
import static org.molgenis.data.semantic.Relation.isAssociatedWith;
import static org.molgenis.data.system.model.RootSystemPackage.PACKAGE_SYSTEM;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.testng.Assert.assertEquals;

@WebAppConfiguration
@ContextConfiguration(classes = GsonConfig.class)
public class MappingServiceControllerTest extends AbstractMolgenisSpringTest
{
	@Autowired
	private EntityTypeFactory entityTypeFactory;

	@Autowired
	private AttributeFactory attrMetaFactory;

	@InjectMocks
	private MappingServiceController controller = new MappingServiceController();

	@Mock
	private UserService userService;

	@Mock
	private MappingJobExecutionFactory mappingJobExecutionFactory;

	@Mock
	private MappingService mappingService;

	@Mock
	private AlgorithmService algorithmService;

	@Mock
	private DataService dataService;

	@Mock
	private OntologyTagService ontologyTagService;

	@Mock
	private SemanticSearchService semanticSearchService;

	@Autowired
	private GsonHttpMessageConverter gsonHttpMessageConverter;

	@Mock
	private MenuReaderService menuReaderService;

	@Mock
	private Model model;

	@Mock
	private MappingTarget mappingTarget;

	@Mock
	private EntityType targetEntityType;

	@Mock
	private EntityType target1;
	@Mock
	private EntityType target2;
	@Mock
	private MetaDataService metaDataService;
	@Mock
	private Package system;
	@Mock
	private Package base;
	@Mock
	private UserAccountService userAccountService;
	@Mock
	private User me;
	@Mock
	private JobExecutor jobExecutor;
	@Mock
	private MappingJobExecution mappingJobExecution;
	@Mock
	private EntityType mappingJobExecutionMetadata;
	@Mock
	private JobsController jobsController;

	private EntityType lifeLines;
	private EntityType hop;
	private MappingProject mappingProject;
	private static final String ID = "mappingservice";
	private Attribute ageAttr;
	private Attribute dobAttr;

	private MockMvc mockMvc;
	private OngoingStubbing<Multimap<Relation, OntologyTerm>> multimapOngoingStubbing;

	@BeforeMethod
	public void beforeTest()
	{
		me = mock(User.class);
		when(me.getUsername()).thenReturn("fdlk");
		TestingAuthenticationToken authentication = new TestingAuthenticationToken("fdlk", null);
		authentication.setAuthenticated(true);
		SecurityContextHolder.getContext().setAuthentication(authentication);

		hop = entityTypeFactory.create("HOP");
		ageAttr = attrMetaFactory.create().setName("age").setDataType(INT);
		hop.addAttribute(ageAttr);
		dobAttr = attrMetaFactory.create().setName("dob").setDataType(DATE);
		hop.addAttribute(dobAttr);
		hop.setAbstract(true);

		lifeLines = entityTypeFactory.create("LifeLines");

		mappingProject = new MappingProject("hop hop hop", me);
		mappingProject.setIdentifier("asdf");
		MappingTarget mappingTarget = mappingProject.addTarget(hop);
		EntityMapping entityMapping = mappingTarget.addSource(lifeLines);
		AttributeMapping attributeMapping = entityMapping.addAttributeMapping("age");
		attributeMapping.setAlgorithm("$('dob').age()");

		when(dataService.getMeta()).thenReturn(metaDataService);

		mockMvc = MockMvcBuilders.standaloneSetup(controller)
								 .setMessageConverters(gsonHttpMessageConverter, new StringHttpMessageConverter())
								 .build();
	}

	@Test
	public void itShouldUpdateExistingAttributeMappingWhenSaving() throws Exception
	{
		when(mappingService.getMappingProject("asdf")).thenReturn(mappingProject);
		Menu menu = mock(Menu.class);
		when(menuReaderService.getMenu()).thenReturn(menu);
		when(menu.findMenuItemPath(ID)).thenReturn("/menu/main/mappingservice");

		mockMvc.perform(post(URI + "/saveattributemapping").param("mappingProjectId", "asdf")
														   .param("target", "HOP")
														   .param("source", "LifeLines")
														   .param("targetAttribute", "age")
														   .param("algorithm", "$('length').value()")
														   .param("algorithmState", "CURATED"))
			   .andExpect(redirectedUrl("/menu/main/mappingservice/mappingproject/asdf"));
		MappingProject expected = new MappingProject("hop hop hop", me);
		expected.setIdentifier("asdf");
		MappingTarget mappingTarget = expected.addTarget(hop);
		EntityMapping entityMapping = mappingTarget.addSource(lifeLines);
		AttributeMapping ageMapping = entityMapping.addAttributeMapping("age");
		ageMapping.setAlgorithm("$('length').value()");
		ageMapping.setAlgorithmState(AttributeMapping.AlgorithmState.CURATED);

		verify(mappingService).updateMappingProject(expected);
	}

	@Test
	public void itShouldCreateNewAttributeMappingWhenSavingIfNonePresent() throws Exception
	{
		when(mappingService.getMappingProject("asdf")).thenReturn(mappingProject);
		Menu menu = mock(Menu.class);
		when(menuReaderService.getMenu()).thenReturn(menu);
		when(menu.findMenuItemPath(ID)).thenReturn("/menu/main/mappingservice");

		mockMvc.perform(post(URI + "/saveattributemapping").param("mappingProjectId", "asdf")
														   .param("target", "HOP")
														   .param("source", "LifeLines")
														   .param("targetAttribute", "height")
														   .param("algorithm", "$('length').value()")
														   .param("algorithmState", "CURATED"))
			   .andExpect(MockMvcResultMatchers.redirectedUrl("/menu/main/mappingservice/mappingproject/asdf"));

		MappingProject expected = new MappingProject("hop hop hop", me);
		expected.setIdentifier("asdf");
		MappingTarget mappingTarget = expected.addTarget(hop);
		EntityMapping entityMapping = mappingTarget.addSource(lifeLines);
		AttributeMapping ageMapping = entityMapping.addAttributeMapping("age");
		ageMapping.setAlgorithm("$('dob').age()");
		AttributeMapping heightMapping = entityMapping.addAttributeMapping("height");
		heightMapping.setAlgorithm("$('length').value()");
		heightMapping.setAlgorithmState(AttributeMapping.AlgorithmState.CURATED);

		verify(mappingService).updateMappingProject(expected);
	}

	@Test
	public void itShouldRemoveEmptyAttributeMappingsWhenSaving() throws Exception
	{
		when(mappingService.getMappingProject("asdf")).thenReturn(mappingProject);
		Menu menu = mock(Menu.class);
		when(menuReaderService.getMenu()).thenReturn(menu);
		when(menu.findMenuItemPath(ID)).thenReturn("/menu/main/mappingservice");

		mockMvc.perform(post(URI + "/saveattributemapping").param("mappingProjectId", "asdf")
														   .param("target", "HOP")
														   .param("source", "LifeLines")
														   .param("targetAttribute", "age")
														   .param("algorithm", "")
														   .param("algorithmState", "CURATED"))
			   .andExpect(MockMvcResultMatchers.redirectedUrl("/menu/main/mappingservice/mappingproject/asdf"));

		MappingProject expected = new MappingProject("hop hop hop", me);
		expected.setIdentifier("asdf");
		MappingTarget mappingTarget = expected.addTarget(hop);
		mappingTarget.addSource(lifeLines);
		verify(mappingService).updateMappingProject(expected);
	}

	@Test
	public void getFirstAttributeMappingInfo_age() throws Exception
	{
		when(mappingService.getMappingProject("asdf")).thenReturn(mappingProject);
		Menu menu = mock(Menu.class);
		when(menuReaderService.getMenu()).thenReturn(menu);
		when(menu.findMenuItemPath(ID)).thenReturn("/menu/main/mappingservice");

		MvcResult result = mockMvc.perform(post(URI + "/firstattributemapping").param("mappingProjectId", "asdf")
																			   .param("target", "HOP")
																			   .param("source", "LifeLines")
																			   .param("skipAlgorithmStates[]",
																					   "CURATED", "DISCUSS")
																			   .accept(MediaType.APPLICATION_JSON))
								  .andReturn();

		String actual = result.getResponse().getContentAsString();

		assertEquals(actual,
				"{\"mappingProjectId\":\"asdf\",\"target\":\"HOP\",\"source\":\"LifeLines\",\"targetAttribute\":\"age\"}");
	}

	@Test
	public void getFirstAttributeMappingInfo_dob() throws Exception
	{
		when(mappingService.getMappingProject("asdf")).thenReturn(mappingProject);
		Menu menu = mock(Menu.class);
		when(menuReaderService.getMenu()).thenReturn(menu);
		when(menu.findMenuItemPath(ID)).thenReturn("/menu/main/mappingservice");

		mappingProject.getMappingTarget("HOP")
					  .getMappingForSource("LifeLines")
					  .getAttributeMapping("age")
					  .setAlgorithmState(AttributeMapping.AlgorithmState.CURATED);

		MvcResult result2 = mockMvc.perform(post(URI + "/firstattributemapping").param("mappingProjectId", "asdf")
																				.param("target", "HOP")
																				.param("source", "LifeLines")
																				.param("skipAlgorithmStates[]",
																						"CURATED", "DISCUSS")
																				.accept(MediaType.APPLICATION_JSON))
								   .andReturn();

		String actual2 = result2.getResponse().getContentAsString();

		assertEquals(actual2,
				"{\"mappingProjectId\":\"asdf\",\"target\":\"HOP\",\"source\":\"LifeLines\",\"targetAttribute\":\"dob\"}");
	}

	@Test
	public void testIsNewEntityReturnsTrueIfEntityIsNew() throws Exception
	{
		MockHttpServletResponse response = mockMvc.perform(
				get(URI + "/isNewEntity").param("targetEntityTypeId", "blah").accept(MediaType.APPLICATION_JSON))
												  .andReturn()
												  .getResponse();
		assertEquals(response.getContentAsString(), "true",
				"When checking for a new entity type, the result should be the String \"true\"");
		assertEquals(response.getContentType(), "application/json");
		verify(dataService).getEntityType("blah");
	}

	@Test
	public void testIsNewEntityReturnsFalseIfEntityExists() throws Exception
	{
		when(dataService.getEntityType("it_emx_test_TypeTest")).thenReturn(mock(EntityType.class));
		MockHttpServletResponse response = mockMvc.perform(
				get(URI + "/isNewEntity").param("targetEntityTypeId", "it_emx_test_TypeTest")
										 .accept(MediaType.APPLICATION_JSON)).andReturn().getResponse();
		assertEquals(response.getContentType(), "application/json");
		assertEquals(response.getContentAsString(), "false",
				"When checking for an existing entity type, the result should be the String \"false\"");
	}

	@Test
	public void getFirstAttributeMappingInfo_none() throws Exception
	{
		when(mappingService.getMappingProject("asdf")).thenReturn(mappingProject);
		Menu menu = mock(Menu.class);
		when(menuReaderService.getMenu()).thenReturn(menu);
		when(menu.findMenuItemPath(ID)).thenReturn("/menu/main/mappingservice");

		mappingProject.getMappingTarget("HOP")
					  .getMappingForSource("LifeLines")
					  .getAttributeMapping("age")
					  .setAlgorithmState(AttributeMapping.AlgorithmState.DISCUSS);

		MappingTarget mappingTarget = mappingProject.getMappingTarget("HOP");
		EntityMapping entityMapping = mappingTarget.getMappingForSource("LifeLines");
		AttributeMapping attributeMapping = entityMapping.addAttributeMapping("dob");
		attributeMapping.setAlgorithm("$('dob').age()");
		attributeMapping.setAlgorithmState(AttributeMapping.AlgorithmState.DISCUSS);

		MvcResult result3 = mockMvc.perform(post(URI + "/firstattributemapping").param("mappingProjectId", "asdf")
																				.param("target", "HOP")
																				.param("source", "LifeLines")
																				.param("skipAlgorithmStates[]",
																						"CURATED", "DISCUSS")
																				.accept(MediaType.APPLICATION_JSON))
								   .andReturn();

		String actual3 = result3.getResponse().getContentAsString();

		assertEquals(actual3, "");
	}

	@Test
	public void testScheduleMappingJobUnknownMappingProjectId() throws Exception
	{
		mockMvc.perform(post(URI + "/map").param("mappingProjectId", "mappingProjectId")
										  .param("targetEntityTypeId", "targetEntityTypeId")
										  .param("label", "label")
										  .param("package", "package")
										  .accept("text/plain"))
			   .andExpect(status().isBadRequest())
			   .andExpect(content().contentType("text/plain"))
			   .andExpect(content().string("No mapping project found with ID mappingProjectId"));
	}

	@Test
	public void testScheduleMappingJobUnknownPackage() throws Exception
	{
		when(mappingService.getMappingProject("mappingProjectId")).thenReturn(mappingProject);

		mockMvc.perform(post(URI + "/map").param("mappingProjectId", "mappingProjectId")
										  .param("targetEntityTypeId", "targetEntityTypeId")
										  .param("label", "label")
										  .param("package", "sys")
										  .accept("text/plain"))
			   .andExpect(status().isBadRequest())
			   .andExpect(content().contentType("text/plain"))
			   .andExpect(content().string("No package found with ID sys"));
	}

	@Test
	public void testScheduleMappingJobSystemPackage() throws Exception
	{
		when(mappingService.getMappingProject("mappingProjectId")).thenReturn(mappingProject);
		Package systemPackage = mock(Package.class);
		when(systemPackage.getId()).thenReturn("sys");
		when(metaDataService.getPackage("sys")).thenReturn(systemPackage);

		mockMvc.perform(post(URI + "/map").param("mappingProjectId", "mappingProjectId")
										  .param("targetEntityTypeId", "targetEntityTypeId")
										  .param("label", "label")
										  .param("package", "sys")
										  .accept("text/plain"))
			   .andExpect(status().isBadRequest())
			   .andExpect(content().contentType("text/plain"))
			   .andExpect(content().string("Package [sys] is a system package."));
	}

	@Test
	public void testMap() throws Exception
	{
		when(mappingService.getMappingProject("mappingProjectId")).thenReturn(mappingProject);
		Package aPackage = mock(Package.class);
		when(aPackage.getId()).thenReturn("base");
		when(aPackage.getRootPackage()).thenReturn(null);
		when(metaDataService.getPackage("base")).thenReturn(aPackage);

		when(mappingJobExecutionFactory.create()).thenReturn(mappingJobExecution);
		when(mappingJobExecution.getEntityType()).thenReturn(mappingJobExecutionMetadata);
		when(mappingJobExecution.getIdValue()).thenReturn("abcde");
		when(mappingJobExecutionMetadata.getId()).thenReturn("MappingJobExecution");

		when(userAccountService.getCurrentUser()).thenReturn(me);

		mockMvc.perform(post(URI + "/map").param("mappingProjectId", "mappingProjectId")
										  .param("targetEntityTypeId", "targetEntityTypeId")
										  .param("label", "label")
										  .param("package", "base")
										  .accept("text/plain"))
			   .andExpect(status().isCreated())
			   .andExpect(content().contentType("text/plain"))
			   .andExpect(content().string("/api/v2/MappingJobExecution/abcde"));

		verify(jobExecutor).submit(mappingJobExecution);
		verify(mappingJobExecution).setMappingProjectId("mappingProjectId");
		verify(mappingJobExecution).setLabel("label");
		verify(mappingJobExecution).setAddSourceAttribute(null);
		verify(mappingJobExecution).setTargetEntityTypeId("targetEntityTypeId");
		verify(mappingJobExecution).setPackageId("base");
		verify(mappingJobExecution).setUser(me);
		verify(mappingJobExecution).getEntityType();
		verify(mappingJobExecution).getIdValue();
		verifyNoMoreInteractions(mappingJobExecution);
	}

	@Test
	public void testCreateIntegratedEntity() throws Exception
	{
		when(mappingService.getMappingProject("mappingProjectId")).thenReturn(mappingProject);
		Package aPackage = mock(Package.class);
		when(aPackage.getId()).thenReturn("base");
		when(aPackage.getRootPackage()).thenReturn(null);
		when(metaDataService.getPackage("base")).thenReturn(aPackage);

		when(mappingJobExecutionFactory.create()).thenReturn(mappingJobExecution);
		when(mappingJobExecution.getEntityType()).thenReturn(mappingJobExecutionMetadata);
		when(mappingJobExecution.getIdValue()).thenReturn("abcde");
		when(mappingJobExecutionMetadata.getId()).thenReturn("MappingJobExecution");

		when(userAccountService.getCurrentUser()).thenReturn(me);
		when(jobsController.createJobExecutionViewHref(mappingJobExecution, 1000)).thenReturn(
				"/jobs/viewJob/?jobHref=jobHref&refreshTimeoutMillis=1000");

		mockMvc.perform(post(URI + "/createIntegratedEntity").param("mappingProjectId", "mappingProjectId")
															 .param("targetEntityTypeId", "targetEntityTypeId")
															 .param("label", "label")
															 .param("package", "base")
															 .accept("text/plain"))
			   .andExpect(status().isFound())
			   .andExpect(header().string("Location", "/jobs/viewJob/?jobHref=jobHref&refreshTimeoutMillis=1000"));

		verify(jobExecutor).submit(mappingJobExecution);
		verify(mappingJobExecution).setMappingProjectId("mappingProjectId");
		verify(mappingJobExecution).setLabel("label");
		verify(mappingJobExecution).setAddSourceAttribute(null);
		verify(mappingJobExecution).setTargetEntityTypeId("targetEntityTypeId");
		verify(mappingJobExecution).setPackageId("base");
		verify(mappingJobExecution).setUser(me);
		verifyNoMoreInteractions(mappingJobExecution);
	}

	@Test
	public void testViewMappingProject()
	{
		when(mappingService.getMappingProject("hop hop hop")).thenReturn(mappingProject);
		when(dataService.getEntityTypeIds()).thenReturn(Stream.of("LifeLines", "entity1", "entity2"));
		when(mappingService.getCompatibleEntityTypes(hop)).thenReturn(Stream.of(lifeLines, target1, target2));

		when(dataService.getEntityType("HOP")).thenReturn(hop);
		OntologyTerm ontologyTermAge = OntologyTerm.create("iri1", "label1");
		OntologyTerm ontologyTermDateOfBirth = OntologyTerm.create("iri2", "label2");
		when(ontologyTagService.getTagsForAttribute(hop, ageAttr)).thenReturn(
				ImmutableMultimap.of(isAssociatedWith, ontologyTermAge));
		when(ontologyTagService.getTagsForAttribute(hop, dobAttr)).thenReturn(
				ImmutableMultimap.of(isAssociatedWith, ontologyTermDateOfBirth));

		when(dataService.getMeta()).thenReturn(metaDataService);
		when(metaDataService.getPackages()).thenReturn(asList(system, base));
		when(system.getId()).thenReturn(PACKAGE_SYSTEM); // system package, not a valid choice
		when(base.getId()).thenReturn("base");
		when(dataService.getEntityType("entity1")).thenReturn(target1);
		when(dataService.getEntityType("entity2")).thenReturn(target2);

		String view = controller.viewMappingProject("hop hop hop", model);

		assertEquals(view, "view-single-mapping-project");
		verify(model).addAttribute("entityTypes", asList(target1, target2));
		verify(model).addAttribute("packages", singletonList(base));
		verify(model).addAttribute("compatibleTargetEntities", asList(lifeLines, target1, target2));
		verify(model).addAttribute("selectedTarget", "HOP");
		verify(model).addAttribute("mappingProject", mappingProject);
		verify(model).addAttribute("hasWritePermission", true);
		verify(model).addAttribute("attributeTagMap",
				ImmutableMap.of("dob", singletonList(ontologyTermDateOfBirth), "age", singletonList(ontologyTermAge)));
	}

}
