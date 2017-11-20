package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseAdminClientImpl;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.SynapseClientImpl;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseNotFoundException;
import org.sagebionetworks.reflection.model.PaginatedResults;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.Reference;
import org.sagebionetworks.repo.model.provenance.Activity;

public class IT930ActivityController {

	private static SynapseAdminClient adminSynapse;
	private static SynapseClient synapse;
	private static Long userToDelete;

	private List<String> entitiesToDelete;
	private List<String> activitiesToDelete;
	
	@BeforeClass 
	public static void beforeClass() throws Exception {
		// Create a user
		adminSynapse = new SynapseAdminClientImpl();
		SynapseClientHelper.setEndpoints(adminSynapse);
		adminSynapse.setUsername(StackConfiguration.getMigrationAdminUsername());
		adminSynapse.setApiKey(StackConfiguration.getMigrationAdminAPIKey());
		
		synapse = new SynapseClientImpl();
		userToDelete = SynapseClientHelper.createUser(adminSynapse, synapse);
	}
	
	@Before
	public void before() throws SynapseException {
		adminSynapse.clearAllLocks();
		entitiesToDelete = new ArrayList<String>();
		activitiesToDelete = new ArrayList<String>();
	}
	
	@After
	public void after() throws Exception {
		for(String id : entitiesToDelete) {
			synapse.deleteAndPurgeEntityById(id);
		}
		for(String id : activitiesToDelete) {
			synapse.deleteActivity(id);
		}
	}
	
	@AfterClass
	public static void afterClass() throws Exception {
		if (userToDelete != null) {
			adminSynapse.deleteUser(userToDelete);
		}
	}

	@Test 
	public void testActivityCrud() throws Exception {
		// create
		Activity act = new Activity();
		act = synapse.createActivity(act);
		activitiesToDelete.add(act.getId());
		assertNotNull(act);
		
		// update
		String newname = "updated";
		act.setName(newname);
		act = synapse.putActivity(act);
		assertEquals(newname, act.getName());
		
		// set generated by
		Project entity = new Project();
		entity.setEntityType(Project.class.getName());
		entity = synapse.createEntity(entity, act.getId());
		entitiesToDelete.add(entity.getId());
		assertEquals(act.getId(), synapse.getActivityForEntity(entity.getId()).getId());
		
		// version
		assertEquals(act.getId(), synapse.getActivityForEntityVersion(entity.getId(), 1L).getId());
		
		// generated
		PaginatedResults<Reference> refs = synapse.getEntitiesGeneratedBy(act.getId(), Integer.MAX_VALUE, 0);
		assertEquals(1, refs.getTotalNumberOfResults());
		Reference ref = refs.getResults().get(0);
		assertEquals(entity.getId(), ref.getTargetId());
		assertEquals(new Long(1), ref.getTargetVersionNumber());
			
	}

}
