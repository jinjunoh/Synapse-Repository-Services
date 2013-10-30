package org.sagebionetworks.repo.web;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.FileHandleDao;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.v2.wiki.V2WikiPage;
import org.sagebionetworks.repo.model.wiki.WikiPage;
import org.sagebionetworks.repo.util.TempFileProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class WikiModelTranslationHelperTest {
	@Autowired
	FileHandleManager fileHandleManager;
	@Autowired
	FileHandleDao fileMetadataDao;	
	@Autowired
	AmazonS3Client s3Client;
	@Autowired
	TempFileProvider tempFileProvider;
	@Autowired
	UserManager userManager;
	@Autowired
	WikiModelTranslator wikiModelTranslationHelper;
	
	private String userName;
	private String ownerId;
	private UserInfo userInfo;
	private String markdownAsString = "Markdown string contents with a link: \n[example](http://url.com/).";

	@Before
	public void before() throws Exception{
		// get user IDs
		userName = AuthorizationConstants.TEST_USER_NAME;
		userInfo = userManager.getUserInfo(userName);
		ownerId = userManager.getUserInfo(userName).getIndividualGroup().getId();
	}
	
	@Test
	public void testConversionRoundTrip() throws IOException, NotFoundException {
		// Setup a WikiPage
		WikiPage wiki = new WikiPage();
		wiki.setId("123");
		wiki.setCreatedBy(ownerId);
		wiki.setModifiedBy(ownerId);
		wiki.setParentWikiId(null);
		wiki.setTitle("v1-wiki");
		wiki.setAttachmentFileHandleIds(new ArrayList<String>());
		wiki.setMarkdown(markdownAsString);
		
		// Pass it to the converter
		V2WikiPage v2Wiki = wikiModelTranslationHelper.convertToV2WikiPage(wiki, userInfo);
		
		// Check fields were copied accurately
		assertEquals(wiki.getId(), v2Wiki.getId());
		assertEquals(wiki.getCreatedBy(), v2Wiki.getCreatedBy());
		assertEquals(wiki.getModifiedBy(), v2Wiki.getModifiedBy());
		assertEquals(wiki.getParentWikiId(), v2Wiki.getParentWikiId());
		assertEquals(wiki.getTitle(), v2Wiki.getTitle());
		assertEquals(wiki.getAttachmentFileHandleIds(), v2Wiki.getAttachmentFileHandleIds());
		
		// Check that the markdown is now a file handle id
		assertNotNull(v2Wiki);
		String markdownHandleId = v2Wiki.getMarkdownFileHandleId();
		assertNotNull(markdownHandleId);
	
		S3FileHandle markdownHandle = (S3FileHandle) fileMetadataDao.get(markdownHandleId);
		File markdownTemp = tempFileProvider.createTempFile(wiki.getId()+ "_markdown", ".tmp");
		// Retrieve uploaded markdown
		ObjectMetadata markdownMeta = s3Client.getObject(new GetObjectRequest(markdownHandle.getBucketName(), 
				markdownHandle.getKey()), markdownTemp);
		// Read the file as a string
		String markdownString = FileUtils.readFileToString(markdownTemp, "UTF-8");
		// Make sure uploaded markdown is accurate
		assertEquals(markdownAsString, markdownString);
		
		// Now pass in V2WikiPage
		WikiPage v1Wiki = wikiModelTranslationHelper.convertToWikiPage(v2Wiki);
		// Make sure the WikiPage's markdown string field is accurate
		assertEquals(markdownAsString, v1Wiki.getMarkdown());
	}
}
