package org.sagebionetworks.repo.manager.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.repo.model.table.QueryOptions.BUNDLE_MASK_QUERY_COLUMN_MODELS;
import static org.sagebionetworks.repo.model.table.QueryOptions.BUNDLE_MASK_QUERY_COUNT;
import static org.sagebionetworks.repo.model.table.QueryOptions.BUNDLE_MASK_QUERY_FACETS;
import static org.sagebionetworks.repo.model.table.QueryOptions.BUNDLE_MASK_QUERY_MAX_ROWS_PER_PAGE;
import static org.sagebionetworks.repo.model.table.QueryOptions.BUNDLE_MASK_QUERY_RESULTS;
import static org.sagebionetworks.repo.model.table.QueryOptions.BUNDLE_MASK_QUERY_SELECT_COLUMNS;
import static org.sagebionetworks.repo.model.table.QueryOptions.BUNDLE_MASK_SUM_FILE_SIZES;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.sagebionetworks.common.util.progress.ProgressCallback;
import org.sagebionetworks.common.util.progress.ProgressingCallable;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dao.table.RowHandler;
import org.sagebionetworks.repo.model.dbo.dao.table.TableModelTestUtils;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnMultiValueFunction;
import org.sagebionetworks.repo.model.table.ColumnMultiValueFunctionQueryFilter;
import org.sagebionetworks.repo.model.table.ColumnSingleValueFilterOperator;
import org.sagebionetworks.repo.model.table.ColumnSingleValueQueryFilter;
import org.sagebionetworks.repo.model.table.DownloadFromTableRequest;
import org.sagebionetworks.repo.model.table.DownloadFromTableResult;
import org.sagebionetworks.repo.model.table.FacetColumnRangeRequest;
import org.sagebionetworks.repo.model.table.FacetColumnRequest;
import org.sagebionetworks.repo.model.table.FacetColumnResult;
import org.sagebionetworks.repo.model.table.FacetColumnResultRange;
import org.sagebionetworks.repo.model.table.FacetColumnResultValues;
import org.sagebionetworks.repo.model.table.FacetType;
import org.sagebionetworks.repo.model.table.Query;
import org.sagebionetworks.repo.model.table.QueryBundleRequest;
import org.sagebionetworks.repo.model.table.QueryOptions;
import org.sagebionetworks.repo.model.table.QueryResult;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.repo.model.table.SortDirection;
import org.sagebionetworks.repo.model.table.SortItem;
import org.sagebionetworks.repo.model.table.SumFileSizes;
import org.sagebionetworks.repo.model.table.TableConstants;
import org.sagebionetworks.repo.model.table.TableFailedException;
import org.sagebionetworks.repo.model.table.TableState;
import org.sagebionetworks.repo.model.table.TableStatus;
import org.sagebionetworks.repo.model.table.TableUnavailableException;
import org.sagebionetworks.repo.model.table.ViewObjectType;
import org.sagebionetworks.repo.model.table.ViewScopeType;
import org.sagebionetworks.repo.model.table.ViewTypeMask;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.SchemaProvider;
import org.sagebionetworks.table.cluster.SqlQuery;
import org.sagebionetworks.table.cluster.SqlQueryBuilder;
import org.sagebionetworks.table.cluster.TableIndexDAO;
import org.sagebionetworks.table.cluster.description.IndexDescription;
import org.sagebionetworks.table.cluster.description.TableIndexDescription;
import org.sagebionetworks.table.cluster.description.ViewIndexDescription;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.table.query.ParseException;
import org.sagebionetworks.table.query.TableQueryParser;
import org.sagebionetworks.table.query.model.QuerySpecification;
import org.sagebionetworks.util.csv.CSVWriterStream;
import org.sagebionetworks.workers.util.semaphore.LockUnavilableException;
import org.springframework.jdbc.BadSqlGrammarException;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

@ExtendWith(MockitoExtension.class)
public class TableQueryManagerImplTest {
	
	@Mock
	private TableManagerSupport mockTableManagerSupport;
	@Mock
	private ConnectionFactory mockTableConnectionFactory;
	@Mock
	private TableIndexDAO mockTableIndexDAO;
	@Mock
	private ProgressCallback mockProgressCallbackVoid;
	@Mock
	private ProgressCallback mockProgressCallback2;
	@InjectMocks
	private TableQueryManagerImpl manager;
	
	private List<ColumnModel> models;
	
	private UserInfo user;
	private String tableId;
	private IdAndVersion idAndVersion;
	private List<Row> rows;
	private TableStatus status;
	private int maxBytesPerRequest;
	private CSVWriterStream writer;
	private List<String[]> writtenLines;
	
	private List<SortItem> sortList;
	private SchemaProvider schemaProvider;
	
	@Captor
	private ArgumentCaptor<Map<String,Object>> paramsCaptor;
	
	private String facetColumnName;
	private String facetMax;
	private FacetColumnRangeRequest facetColumnRequest;
	private FacetColumnResultRange expectedRangeResult;
	private RowSet enumerationFacetResults;
	private RowSet rangeFacetResults;
	
	private QueryOptions queryOptions;
	
	private Long sumFilesizes;
	
	private HashSet<Long> benfactors;
	private HashSet<Long> subSet;
	
	private ViewScopeType scopeType;
	
	@BeforeEach
	public void before() throws Exception {
		tableId = "syn123";
		idAndVersion = IdAndVersion.parse(tableId);
		user = new UserInfo(false, 7L);
		
		status = new TableStatus();
		status.setTableId(tableId);
		status.setState(TableState.AVAILABLE);
		status.setChangedOn(new Date(123));
		status.setLastTableChangeEtag("etag");
		
		models = TableModelTestUtils.createOneOfEachType(true);		
		
		schemaProvider = (IdAndVersion tableId) -> {
			return models;
		};
		
		maxBytesPerRequest = 10000000;
		manager.setMaxBytesPerRequest(maxBytesPerRequest);
		
		rows = TableModelTestUtils.createRows(models, 10);
		
		// Writer that captures lines
		writtenLines = new LinkedList<String[]>();
		writer = new CSVWriterStream() {

			@Override
			public void writeNext(String[] nextLine) {
				writtenLines.add(nextLine);
			}
		};

		SortItem sort = new SortItem();
		sort.setColumn("i0");
		sort.setDirection(SortDirection.DESC);
		sortList = Lists.newArrayList(sort);
		
		benfactors = Sets.newHashSet(333L,444L);
		subSet = Sets.newHashSet(444L);
		
		facetColumnName = "i2";
		facetMax = "45";
		facetColumnRequest = new FacetColumnRangeRequest();
		facetColumnRequest.setColumnName(facetColumnName);
		facetColumnRequest.setMax(facetMax);
		
		String expectedColMin = "100";
		String expectedColMax = "123";
		String expectedColumnName = "i2";
		FacetType expectedFacetType = FacetType.range;
		enumerationFacetResults = createRowSetForTest(Lists.newArrayList(FacetTransformerValueCounts.VALUE_ALIAS, FacetTransformerValueCounts.COUNT_ALIAS));
		rangeFacetResults = createRowSetForTest(Lists.newArrayList(FacetTransformerRange.MIN_ALIAS, FacetTransformerRange.MAX_ALIAS), Lists.newArrayList(expectedColMin, expectedColMax));
		expectedRangeResult = new FacetColumnResultRange();
		expectedRangeResult.setColumnName(expectedColumnName);
		expectedRangeResult.setColumnMin(expectedColMin);
		expectedRangeResult.setFacetType(expectedFacetType);
		expectedRangeResult.setColumnMax(expectedColMax);
		
		queryOptions = new QueryOptions().withRunQuery(true);
		sumFilesizes = 9876L;
		scopeType =  new ViewScopeType(ViewObjectType.ENTITY, ViewTypeMask.File.getMask());
	}

	void setupQueryCallback() {
		when(mockTableIndexDAO.queryAsStream(any(ProgressCallback.class),any(SqlQuery.class), any(RowHandler.class))).thenAnswer(new Answer<Boolean>() {
			@Override
			public Boolean answer(InvocationOnMock invocation) throws Throwable {
				RowHandler handler =  (RowHandler) invocation.getArguments()[2];
				// Pass all rows to the handler
				for (Row row : rows) {
					handler.nextRow(row);
				}
				return true;
			}
		});
	}

	void setupNonExclusiveLock() throws Exception {
		// Just call the caller.
		when(mockTableManagerSupport.tryRunWithTableNonexclusiveLock(any(ProgressCallback.class),
				any(ProgressingCallable.class), any(IdAndVersion.class))).thenAnswer(new Answer<Object>() {
			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {
				if(invocation == null) return null;
				ProgressingCallable<Object> callable = (ProgressingCallable<Object>) invocation.getArguments()[1];
						if (callable != null) {
							return callable.call(mockProgressCallback2);
						} else {
							return null;
						}
			}
		});
	}
	
	/**
	 * Add RowId and RowVersion to rows.
	 */
	public void addRowIdAndVersionToRows(){
		long id = 0;
		long version = 101;
		for(Row row: rows){
			row.setRowId(id++);
			row.setVersionNumber(version);
		}
	}
	
	/**
	 * Convert each row to an entity row.
	 */
	public void convertRowsToEntityRows(){
		int count = 0;
		List<Row> newRows = new LinkedList<Row>();
		for(Row row: rows){
			Row newRow = new Row();
			newRow.setRowId(row.getRowId());
			newRow.setVersionNumber(row.getVersionNumber());
			newRow.setEtag("etag-"+count);
			newRow.setValues(row.getValues());
			count++;
			newRows.add(newRow);
		}
		this.rows = newRows;
	}

	@Test
	public void testQueryPreflightUnauthroized() throws Exception {
		doThrow(new UnauthorizedException()).when(mockTableManagerSupport).validateTableReadAccess(any(), any());
		Query query = new Query();
		query.setSql("select * from " + tableId);
		assertThrows(UnauthorizedException.class, ()->{
			manager.queryPreflight(user, query, null);
		});
	}
	
	@Test
	public void testQueryPreflightAuthorized() throws Exception {
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		
		Query query = new Query();
		query.setSql("select * from " + tableId);
		manager.queryPreflight(user, query, null);
		verify(mockTableManagerSupport).validateTableReadAccess(user, indexDescription);
	}
	
	@Test
	public void testQueryAsStream() throws Exception{
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		setupNonExclusiveLock();
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		setupQueryCallback();
		
		RowHandler rowHandler = new SinglePageRowHandler();
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId())
		.indexDescription(new TableIndexDescription(idAndVersion)).build();
		// call under test.
		QueryResultBundle result = manager.queryAsStream(mockProgressCallbackVoid, user, query, rowHandler, queryOptions);
		assertNotNull(result);
		assertNotNull(result.getQueryResult());
		assertNotNull(result.getQueryResult().getQueryResults());
		assertEquals(status.getLastTableChangeEtag(), result.getQueryResult().getQueryResults().getEtag());
		// an exclusive lock must be held for a consistent query.
		verify(mockTableManagerSupport).tryRunWithTableNonexclusiveLock(any(ProgressCallback.class), any(ProgressingCallable.class), any(IdAndVersion.class));
		// The table status should be checked only for a consistent query.
		verify(mockTableManagerSupport).getTableStatusOrCreateIfNotExists(idAndVersion);
	}
	
	@Test
	public void testQueryAsStreamNotFoundException() throws Exception{
		when(mockTableManagerSupport.tryRunWithTableNonexclusiveLock(
						any(ProgressCallback.class), any(ProgressingCallable.class),
						any(IdAndVersion.class))).thenThrow(
				new NotFoundException("not found"));
		RowHandler rowHandler = new SinglePageRowHandler();
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId())
		.indexDescription(new TableIndexDescription(idAndVersion)).build();
		assertThrows(NotFoundException.class, ()->{
			// call under test.
			manager.queryAsStream(mockProgressCallbackVoid, user, query, rowHandler, queryOptions);
		});
	}
	
	@Test
	public void testQueryAsStreamTableUnavailableException() throws Exception{
		when(mockTableManagerSupport.tryRunWithTableNonexclusiveLock(
						any(ProgressCallback.class), any(ProgressingCallable.class),
						any(IdAndVersion.class))).thenThrow(
				new TableUnavailableException(new TableStatus()));
		RowHandler rowHandler = new SinglePageRowHandler();
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId())
		.indexDescription(new TableIndexDescription(idAndVersion)).build();
		assertThrows(TableUnavailableException.class, ()->{
			// call under test.
			manager.queryAsStream(mockProgressCallbackVoid, user, query, rowHandler, queryOptions);
		});
	}
	
	@Test
	public void testQueryAsStreamTableFailedException() throws Exception{
		when(mockTableManagerSupport.tryRunWithTableNonexclusiveLock(
						any(ProgressCallback.class), any(ProgressingCallable.class),
						any(IdAndVersion.class))).thenThrow(
				new TableFailedException(new TableStatus()));
		RowHandler rowHandler = new SinglePageRowHandler();
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId())
		.indexDescription(new TableIndexDescription(idAndVersion)).build();
		assertThrows(TableFailedException.class, ()->{
			// call under test.
			manager.queryAsStream(mockProgressCallbackVoid, user, query, rowHandler, queryOptions);
		});
	}
	
	@Test
	public void testQueryAsStreamLockUnavilableException() throws Exception{
		when(mockTableManagerSupport.tryRunWithTableNonexclusiveLock(
						any(ProgressCallback.class),any(ProgressingCallable.class),
						any(IdAndVersion.class))).thenThrow(
				new LockUnavilableException());
		RowHandler rowHandler = new SinglePageRowHandler();
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId())
		.indexDescription(new TableIndexDescription(idAndVersion)).build();
		assertThrows(LockUnavilableException.class, ()->{
			// call under test.
			manager.queryAsStream(mockProgressCallbackVoid, user, query, rowHandler, queryOptions);
		});

	}
	
	@Test
	public void testQueryAsStreamEmptyResultException() throws Exception{
		when(mockTableManagerSupport.tryRunWithTableNonexclusiveLock(
						any(ProgressCallback.class),any(ProgressingCallable.class),
						any(IdAndVersion.class))).thenThrow(
				new EmptyResultException());
		RowHandler rowHandler = new SinglePageRowHandler();
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId())
		.indexDescription(new TableIndexDescription(idAndVersion)).build();
		assertThrows(EmptyResultException.class, ()->{
			// call under test.
			manager.queryAsStream(mockProgressCallbackVoid, user, query, rowHandler, queryOptions);
		});
	}
	
	@Test
	public void testQueryPreflightWithAuthorizationTableEntity() throws Exception{
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		
		Query query = new Query();
		query.setSql("select i0 from "+tableId);
		Long maxBytesPerPage = null;
		manager.queryPreflight(user, query, maxBytesPerPage);
		
		// auth check should occur
		verify(mockTableManagerSupport).validateTableReadAccess(user, indexDescription);
		// a benefactor check should not occur for TableEntities
		verify(mockTableManagerSupport, never()).getAccessibleBenefactors(any(), any(), any());
	}
	
	@Test
	public void testQueryPreflightWithAuthorizationFileView() throws Exception{
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		
		IndexDescription indexDescription = new ViewIndexDescription(idAndVersion, EntityType.entityview);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		
		when(mockTableIndexDAO.getDistinctLongValues(any(), any())).thenReturn(benfactors);
		when(mockTableManagerSupport.getAccessibleBenefactors(any(), any(), any())).thenReturn(subSet);
		
		Query query = new Query();
		query.setSql("select count(*) from "+tableId);
		Long maxBytesPerPage = null;
		// call under test
		SqlQuery results = manager.queryPreflight(user, query, maxBytesPerPage);
		assertNotNull(results);
		// auth check should occur
		verify(mockTableManagerSupport).validateTableReadAccess(user, indexDescription);
		// a benefactor check must occur for FileViews
		verify(mockTableManagerSupport).getAccessibleBenefactors(any(), any(), any());
		// validate the benefactor filter is applied
		assertEquals("SELECT COUNT(*) FROM T123 WHERE ROW_BENEFACTOR IN ( :b0 )", results.getOutputSQL());
		verify(mockTableManagerSupport).getAccessibleBenefactors(user, ObjectType.ENTITY, benfactors);
		verify(mockTableIndexDAO).getDistinctLongValues(idAndVersion, TableConstants.ROW_BENEFACTOR);
	}
	
	@Test
	public void testQueryAsStreamAfterAuthorizationCountOnly() throws Exception {
		when(mockTableIndexDAO.countQuery(anyString(), anyMap())).thenReturn(10L);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		
		Long count = 201L;
		// setup count results
		when(mockTableIndexDAO.countQuery(anyString(), anyMap())).thenReturn(count);
		// null handler indicates not to run the main query.
		RowHandler rowHandler = null;
		queryOptions = new QueryOptions().withRunCount(true);
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		// call under test
		QueryResultBundle results = manager.queryAsStreamAfterAuthorization(mockProgressCallbackVoid, query, rowHandler, queryOptions);
		assertNotNull(results);
		assertEquals(null, results.getColumnModels());
		assertEquals(null, results.getSelectColumns());
		assertEquals(count, results.getQueryCount());
		assertNull(results.getQueryResult());
	}
	
	@Test
	public void testQueryAsStreamAfterAuthorizationColumnsOnly() throws Exception {
		// null handler indicates not to run the main query.
		RowHandler rowHandler = null;
		queryOptions = new QueryOptions().withReturnColumnModels(true);
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		// call under test
		QueryResultBundle results = manager.queryAsStreamAfterAuthorization(mockProgressCallbackVoid, query, rowHandler, queryOptions);
		assertNotNull(results);
		assertEquals(models, results.getColumnModels());
	}
	
	@Test
	public void testQueryAsStreamAfterAuthorizationSelectOnly() throws Exception {
		// null handler indicates not to run the main query.
		RowHandler rowHandler = null;
		queryOptions = new QueryOptions().withReturnSelectColumns(true);
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		// call under test
		QueryResultBundle results = manager.queryAsStreamAfterAuthorization(mockProgressCallbackVoid, query, rowHandler, queryOptions);
		assertNotNull(results);
		assertEquals(TableModelUtils.getSelectColumns(models), results.getSelectColumns());
	}
	
	/**
	 * A limit included in the query limits the count.
	 * @throws Exception
	 */
	@Test
	public void testQueryAsStreamAfterAuthorizationWithLimit() throws Exception {
		when(mockTableIndexDAO.countQuery(anyString(), anyMap())).thenReturn(10L);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		
		Long count = 201L;
		// setup count results
		when(mockTableIndexDAO.countQuery(anyString(), anyMap())).thenReturn(count);
		// null handler indicates not to run the main query.
		RowHandler rowHandler = null;
		queryOptions = new QueryOptions().withRunCount(true);
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId+" limit 11", schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		// call under test
		QueryResultBundle results = manager.queryAsStreamAfterAuthorization(mockProgressCallbackVoid, query, rowHandler, queryOptions);
		assertNotNull(results);
		assertEquals(new Long(11), results.getQueryCount());
	}
	
	@Test
	public void testQueryAsStreamAfterAuthorizationNoCount() throws Exception {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		setupQueryCallback();
		
		// non-null handler indicates the query should be run.
		RowHandler rowHandler = new SinglePageRowHandler();
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		// call under test
		QueryResultBundle results = manager.queryAsStreamAfterAuthorization(mockProgressCallbackVoid, query, rowHandler, queryOptions);
		assertNotNull(results);
		assertEquals(null, results.getColumnModels());
		assertEquals(null, results.getSelectColumns());
		assertNull(results.getQueryCount());
		assertNotNull(results.getQueryResult());
		assertNotNull(results.getQueryResult().getQueryResults());
	}
	
	@Test
	public void testQueryAsStreamAfterAuthorizationQueryAndCount() throws Exception {
		when(mockTableIndexDAO.countQuery(anyString(), anyMap())).thenReturn(10L);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		setupQueryCallback();
		
		Long count = 201L;
		// setup count results
		when(mockTableIndexDAO.countQuery(anyString(), anyMap())).thenReturn(count);
		// non-null handler indicates the query should be run.
		RowHandler rowHandler = new SinglePageRowHandler();
		queryOptions = new QueryOptions().withRunCount(true).withRunQuery(true);
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		// call under test
		QueryResultBundle results = manager.queryAsStreamAfterAuthorization(mockProgressCallbackVoid, query, rowHandler, queryOptions);
		assertNotNull(results);
		assertEquals(null, results.getColumnModels());
		assertEquals(null, results.getSelectColumns());
		assertEquals(null, results.getLastUpdatedOn());
		assertEquals(count, results.getQueryCount());
		assertNotNull(results.getQueryResult());
		assertNotNull(results.getQueryResult().getQueryResults());
	}
	
	@Test
	public void testQueryAsStreamAfterAuthorization_LastUpdatedOn() throws Exception {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		setupQueryCallback();
		
		Date lastUpdatedOn = new Date(567L);
		when(mockTableManagerSupport.getLastChangedOn(idAndVersion)).thenReturn(lastUpdatedOn);

		// non-null handler indicates the query should be run.
		RowHandler rowHandler = new SinglePageRowHandler();
		queryOptions = new QueryOptions().withReturnLastUpdatedOn(true);
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		// call under test
		QueryResultBundle results = manager.queryAsStreamAfterAuthorization(mockProgressCallbackVoid, query, rowHandler, queryOptions);
		assertNotNull(results);
		assertEquals(lastUpdatedOn, results.getLastUpdatedOn());
	}
	
	

	@Test
	public void testQueryAsStreamAfterAuthorizationNonEmptyFacetColumnsListNotReturningFacets() throws Exception{
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		
		Long count = 201L;
		// setup count results
		ArgumentCaptor<String> queryStringCaptor = ArgumentCaptor.forClass(String.class);
		//capture the query to check that the queryToRun is result of appendFacetSearchCondition() and not the original query
		when(mockTableIndexDAO.countQuery(queryStringCaptor.capture(), paramsCaptor.capture())).thenReturn(count);

		List<FacetColumnRequest> facetRequestList = new ArrayList<>();
		facetRequestList.add(facetColumnRequest);
		
		RowHandler rowHandler = null;
		queryOptions = new QueryOptions().withRunCount(true).withReturnColumnModels(true).withReturnSelectColumns(true);
		
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId())
		.selectedFacets(facetRequestList)
		.indexDescription(new TableIndexDescription(idAndVersion)).build();
		
		assertEquals(1, facetRequestList.size());
		
		QueryResultBundle results = manager.queryAsStreamAfterAuthorization(mockProgressCallbackVoid, query, rowHandler, queryOptions);
		assertNotNull(results);
		assertEquals(models, results.getColumnModels());
		assertEquals(TableModelUtils.getSelectColumns(models), results.getSelectColumns());
		assertEquals(count, results.getQueryCount());
		assertNull(results.getQueryResult());
		
		//check to make sure count query was run using a SqlQuery with an facet WHERE clause
		assertTrue(queryStringCaptor.getValue().contains("WHERE ( ( _C2_ <= :b0 ) )"));
		Map<String, Object> capturedParams = paramsCaptor.getValue();
		assertFalse(capturedParams.isEmpty());
		assertEquals(facetMax, capturedParams.get("b0").toString());
	}
	
	@Test
	public void testQueryAsStreamAfterAuthorizationNonEmptyFacetColumnsListReturnFacets() throws Exception {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		
		when(mockTableIndexDAO.query(isNull(), any(SqlQuery.class))).thenReturn(enumerationFacetResults, rangeFacetResults, enumerationFacetResults);
		List<FacetColumnRequest> facetRequestList = new ArrayList<>();
		facetRequestList.add(facetColumnRequest);
		expectedRangeResult.setSelectedMin(facetColumnRequest.getMin());
		expectedRangeResult.setSelectedMax(facetColumnRequest.getMax());

		RowHandler rowHandler = null;
		queryOptions = new QueryOptions().withReturnFacets(true).withRunCount(false);
		
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId())
		.selectedFacets(facetRequestList)
		.indexDescription(new TableIndexDescription(idAndVersion)).build();
		
		assertEquals(1, facetRequestList.size());
		
		QueryResultBundle results = manager.queryAsStreamAfterAuthorization(mockProgressCallbackVoid, query, rowHandler, queryOptions);
		assertNotNull(results);
		assertNull(results.getColumnModels());
		assertNull(results.getSelectColumns());
		assertNull(results.getQueryResult());
		assertNull(results.getQueryCount());
		
		//facet result asserts
		assertNotNull(results.getFacets());
		assertEquals(3, results.getFacets().size());
		FacetColumnResult facetResultColumn = results.getFacets().get(1);
		assertEquals(expectedRangeResult, facetResultColumn);
	}
	
	@Test
	public void testQueryAsStreamAfterAuthorizationWithSearchDisabled() throws Exception {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockTableIndexDAO.isSearchEnabled(any())).thenReturn(false);
		
		// null handler indicates not to run the main query.
		RowHandler rowHandler = null;
		queryOptions = new QueryOptions();
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId+" where text_matches('test')", schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		
		String message = assertThrows(IllegalArgumentException.class, () -> {			
			// call under test
			manager.queryAsStreamAfterAuthorization(mockProgressCallbackVoid, query, rowHandler, queryOptions);
		}).getMessage();
		
		assertEquals("Invalid use of TEXT_MATCHES. Full text search is not enabled on table syn123.", message);

	}
	
	
	@Test
	public void testRunQueryAsStream() throws ParseException{
		setupQueryCallback();
		
		SinglePageRowHandler rowHandler = new SinglePageRowHandler();
		SqlQuery query = new SqlQueryBuilder("select * from " + tableId, schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		// call under test
		RowSet rowSet = manager.runQueryAsStream(mockProgressCallbackVoid, query, rowHandler, mockTableIndexDAO);
		assertNotNull(rowSet);
		assertEquals(TableModelUtils.getSelectColumns(models), rowSet.getHeaders());
		assertEquals(tableId, rowSet.getTableId());
		assertEquals(rows, rowHandler.getRows());
	}
	
	@Test
	public void testCreateEmptyBundleAll(){
		// all options
		queryOptions = new QueryOptions().withMask(-1L);
		QueryResultBundle results = TableQueryManagerImpl.createEmptyBundle(tableId, queryOptions);
		assertNotNull(results.getQueryResult());
		assertNotNull(results.getQueryResult().getQueryResults());
		assertNull(results.getQueryResult().getQueryResults().getEtag());
		assertNotNull(results.getQueryResult().getQueryResults().getHeaders());
		assertNotNull(results.getQueryResult().getQueryResults().getRows());
		assertEquals(tableId, results.getQueryResult().getQueryResults().getTableId());
		assertEquals(new Long(0), results.getQueryCount());
		assertEquals(new Long(1), results.getMaxRowsPerPage());
		assertNotNull(results.getColumnModels());
		assertTrue(results.getColumnModels().isEmpty());
		assertNotNull(results.getSelectColumns());
		assertTrue(results.getSelectColumns().isEmpty());
		assertNotNull(results.getSumFileSizes());
		assertFalse(results.getSumFileSizes().getGreaterThan());
		assertEquals(new Long(0), results.getSumFileSizes().getSumFileSizesBytes());
	}
	
	@Test
	public void testCreateEmptyBundleNone(){
		// no options
		queryOptions = new QueryOptions();
		QueryResultBundle results = TableQueryManagerImpl.createEmptyBundle(tableId, queryOptions);
		assertEquals(null, results.getQueryResult());
		assertEquals(null, results.getQueryCount());
		assertEquals(null, results.getMaxRowsPerPage());
		assertEquals(null, results.getColumnModels());
		assertEquals(null, results.getSelectColumns());
		assertEquals(null, results.getSumFileSizes());
	}
	
	@Test
	public void testCreateEmptyBundleColumnModles(){
		// all options
		queryOptions = new QueryOptions().withReturnColumnModels(true);
		QueryResultBundle results = TableQueryManagerImpl.createEmptyBundle(tableId, queryOptions);
		assertNotNull(results.getColumnModels());
		assertTrue(results.getColumnModels().isEmpty());
	}
	
	@Test
	public void testCreateEmptyBundleCount(){
		// all options
		queryOptions = new QueryOptions().withRunCount(true);
		QueryResultBundle results = TableQueryManagerImpl.createEmptyBundle(tableId, queryOptions);
		assertEquals(new Long(0), results.getQueryCount());
	}
	
	@Test
	public void testCreateEmptyBundleQuery(){
		// all options
		queryOptions = new QueryOptions().withRunQuery(true);
		QueryResultBundle results = TableQueryManagerImpl.createEmptyBundle(tableId, queryOptions);
		assertNotNull(results.getQueryResult());
		assertNotNull(results.getQueryResult().getQueryResults());
	}
	
	@Test
	public void testCreateEmptyBundleMaxRows(){
		// all options
		queryOptions = new QueryOptions().withReturnMaxRowsPerPage(true);
		QueryResultBundle results = TableQueryManagerImpl.createEmptyBundle(tableId, queryOptions);
		assertEquals(new Long(1), results.getMaxRowsPerPage());
	}
	
	@Test
	public void testCreateEmptyBundleSumFileSizes(){
		// all options
		queryOptions = new QueryOptions().withRunSumFileSizes(true);
		QueryResultBundle results = TableQueryManagerImpl.createEmptyBundle(tableId, queryOptions);
		assertNotNull(results.getSumFileSizes());
		assertFalse(results.getSumFileSizes().getGreaterThan());
		assertEquals(new Long(0), results.getSumFileSizes().getSumFileSizesBytes());
	}
	

	@Test 
	public void testQuerySinglePageEmptySchema() throws Exception {
		// Return no columns
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn(0L);
		Query query = new Query();
		query.setSql("select * from " + tableId + " limit 1");
		queryOptions = new QueryOptions().withRunQuery(true).withRunCount(false).withReturnFacets(false);
		QueryResultBundle results = manager.querySinglePage(mockProgressCallbackVoid, user, query, queryOptions);
		assertNotNull(results);
		QueryResultBundle emptyResults = TableQueryManagerImpl.createEmptyBundle(tableId, queryOptions);
		assertEquals(emptyResults, results);
	}
	
	/**
	 * Test for a consistent query when the table index is not available.
	 * @throws Exception
	 */
	@Test
	public void testQueryIndexNotAvailable() throws Exception {
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		setupNonExclusiveLock();
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		
		status.setState(TableState.PROCESSING);
		Query query = new Query();
		query.setSql("select * from " + tableId + " limit 1");
		queryOptions = new QueryOptions().withRunQuery(true).withRunCount(false).withReturnFacets(false);
		TableUnavailableException result = assertThrows(TableUnavailableException.class, ()-> {
			// call under test
			manager.querySinglePage(mockProgressCallbackVoid, user, query, queryOptions);
		});
		assertEquals(status, result.getStatus());
		verify(mockTableManagerSupport, times(1)).getTableStatusOrCreateIfNotExists(idAndVersion);
		verify(mockTableManagerSupport).validateTableReadAccess(user, indexDescription);
	}
	
	
	@Test
	public void runQueryBundleTest()
			throws Exception {
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		setupNonExclusiveLock();
		when(mockTableIndexDAO.countQuery(anyString(), anyMap())).thenReturn(10L);
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		setupQueryCallback();
		
		List<SelectColumn> selectColumns = TableModelUtils.getSelectColumns(models);
		int maxRowSizeBytes = TableModelUtils.calculateMaxRowSize(models);
		maxBytesPerRequest = maxRowSizeBytes*10;
		manager.setMaxBytesPerRequest(maxBytesPerRequest);
		Long maxRowsPerPage = new Long(maxBytesPerRequest/maxRowSizeBytes);
		// setup the count
		Long count = 101L;
		when(mockTableIndexDAO.countQuery(anyString(), anyMap())).thenReturn(count);
		
		Query query = new Query();
		query.setSql("select * from " + tableId);
		query.setOffset(0L);
		query.setLimit(Long.MAX_VALUE);
		QueryBundleRequest queryBundle = new QueryBundleRequest();
		queryBundle.setQuery(query);

		// Request query only
		queryBundle.setPartMask(BUNDLE_MASK_QUERY_RESULTS);
		QueryResultBundle bundle = manager.queryBundle(mockProgressCallbackVoid, user, queryBundle);
		assertEquals(rows, bundle.getQueryResult().getQueryResults().getRows());
		assertEquals(null, bundle.getQueryCount());
		assertEquals(null, bundle.getSelectColumns());
		assertEquals(null, bundle.getMaxRowsPerPage());

		// Count only
		queryBundle.setPartMask(BUNDLE_MASK_QUERY_COUNT);
		bundle = manager.queryBundle(mockProgressCallbackVoid, user, queryBundle);
		assertEquals(null, bundle.getQueryResult());
		assertEquals(count, bundle.getQueryCount());
		assertEquals(null, bundle.getSelectColumns());
		assertEquals(null, bundle.getMaxRowsPerPage());

		// select columns
		queryBundle.setPartMask(BUNDLE_MASK_QUERY_SELECT_COLUMNS);
		bundle = manager.queryBundle(mockProgressCallbackVoid, user, queryBundle);
		assertEquals(null, bundle.getQueryResult());
		assertEquals(null, bundle.getQueryCount());
		assertEquals(selectColumns, bundle.getSelectColumns());
		assertEquals(null, bundle.getMaxRowsPerPage());

		// max rows per page
		queryBundle.setPartMask(BUNDLE_MASK_QUERY_MAX_ROWS_PER_PAGE);
		bundle = manager.queryBundle(mockProgressCallbackVoid, user, queryBundle);
		assertEquals(null, bundle.getQueryResult());
		assertEquals(null, bundle.getQueryCount());
		assertEquals(null, bundle.getSelectColumns());
		assertEquals(maxRowsPerPage, bundle.getMaxRowsPerPage());
		
		// max rows per page
		queryBundle.setPartMask(BUNDLE_MASK_QUERY_COLUMN_MODELS);
		bundle = manager.queryBundle(mockProgressCallbackVoid, user, queryBundle);
		assertEquals(null, bundle.getQueryResult());
		assertEquals(null, bundle.getQueryCount());
		assertEquals(null, bundle.getSelectColumns());
		assertEquals(models, bundle.getColumnModels());

		// now combine them all
		queryBundle.setPartMask(BUNDLE_MASK_QUERY_RESULTS | BUNDLE_MASK_QUERY_COUNT
				| BUNDLE_MASK_QUERY_SELECT_COLUMNS | BUNDLE_MASK_QUERY_MAX_ROWS_PER_PAGE);
		bundle = manager.queryBundle(mockProgressCallbackVoid, user, queryBundle);
		assertEquals(rows, bundle.getQueryResult().getQueryResults().getRows());
		assertEquals(count, bundle.getQueryCount());
		assertEquals(selectColumns, bundle.getSelectColumns());
		assertEquals(maxRowsPerPage, bundle.getMaxRowsPerPage());
	}
	
	@Test
	public void testQueryBundleFacets() throws Exception{
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		setupNonExclusiveLock();
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		
		when(mockTableIndexDAO.query(isNull(), any(SqlQuery.class))).thenReturn(enumerationFacetResults, rangeFacetResults, enumerationFacetResults);
		
		Query query = new Query();
		query.setSql("select * from " + tableId);
		query.setOffset(0L);
		query.setLimit(Long.MAX_VALUE);
		QueryBundleRequest queryBundle = new QueryBundleRequest();
		queryBundle.setQuery(query);
		
		queryBundle.setPartMask(BUNDLE_MASK_QUERY_FACETS);
		QueryResultBundle bundle = manager.queryBundle(mockProgressCallbackVoid, user, queryBundle);
		assertNull(bundle.getQueryResult());
		assertNull(bundle.getQueryCount());
		assertNull(bundle.getSelectColumns());
		assertNull(bundle.getColumnModels());
		assertNotNull(bundle.getFacets());
		assertEquals(3, bundle.getFacets().size());
		//we don't care about the first facet result because it has no useful data and only exists to make sure for loops work
		assertEquals(expectedRangeResult, bundle.getFacets().get(1));
	}
	
	@Test
	public void testQueryBundleSumFileSizes() throws LockUnavilableException, TableUnavailableException, TableFailedException {
		QueryBundleRequest queryBundle = new QueryBundleRequest();
		Query query = new Query();
		query.setSql("select * from " + tableId);
		queryBundle.setQuery(query);
		queryBundle.setPartMask(BUNDLE_MASK_SUM_FILE_SIZES);
		// call under test
		QueryResultBundle bundle = manager.queryBundle(mockProgressCallbackVoid, user, queryBundle);
		assertNotNull(bundle);
		assertNotNull(bundle.getSumFileSizes());
		assertFalse(bundle.getSumFileSizes().getGreaterThan());
		assertEquals(new Long(0),bundle.getSumFileSizes().getSumFileSizesBytes());
	}
	
	@Test
	public void testGetMaxRowsPerPage(){
		Long maxRows = this.manager.getMaxRowsPerPage(models);
		int maxRowSize = TableModelUtils.calculateMaxRowSize(models);
		Long expected = (long) (this.maxBytesPerRequest/maxRowSize);
		assertEquals(expected, maxRows);
	}
	
	@Test
	public void testGetMaxRowsPerPageEmpty(){
		Long maxRows = this.manager.getMaxRowsPerPage(new LinkedList<ColumnModel>());
		assertEquals(null, maxRows);
	}

	
	@Test
	public void testValidateTableIsAvailableWithStateAvailable() throws NotFoundException, TableUnavailableException, TableFailedException{
		status.setState(TableState.AVAILABLE);
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		// call under test
		TableStatus resultsStatus = manager.validateTableIsAvailable(tableId);
		assertNotNull(resultsStatus);
		assertEquals(status, resultsStatus);
	}
	
	@Test
	public void testValidateTableIsAvailableWithStateProcessing() throws NotFoundException, TableUnavailableException, TableFailedException{
		status.setState(TableState.PROCESSING);
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		assertThrows(TableUnavailableException.class, ()->{
			// call under test
			manager.validateTableIsAvailable(tableId);
		});
	}
	
	@Test
	public void testValidateTableIsAvailableWithStateFailed() throws NotFoundException, TableUnavailableException, TableFailedException{
		status.setState(TableState.PROCESSING_FAILED);
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		assertThrows(TableFailedException.class, ()->{
			// call under test
			manager.validateTableIsAvailable(tableId);
		});
	}
	
	@Test
	public void testQueryPreflightSelectStar() throws Exception {
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
	
		List<SortItem> sortList= null;
		Query query = new Query();
		query.setSql("select * from "+tableId);
		query.setSort(sortList);
		Long maxBytesPerPage = null;
		// call under test
		SqlQuery result = manager.queryPreflight(user, query, maxBytesPerPage);
		assertNotNull(result);
		assertEquals("SELECT \"i0\", \"i1\", \"i2\", \"i3\", \"i4\", \"i5\", \"i6\", \"i7\", \"i8\", \"i9\", \"i10\", \"i11\", \"i12\", \"i13\", \"i14\", \"i15\", \"i16\", \"i17\" FROM syn123", result.getModel().toSql());
	}
	
	@Test
	public void testQueryPreflightOverrideSort() throws Exception {
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		
		SortItem sort = new SortItem();
		sort.setColumn("i0");
		sort.setDirection(SortDirection.DESC);
		List<SortItem> sortList= Lists.newArrayList(sort);
		Query query = new Query();
		query.setSql("select i2, i0 from "+tableId);
		query.setSort(sortList);
		Long maxBytesPerPage = null;
		// call under test
		SqlQuery result = manager.queryPreflight(user, query, maxBytesPerPage);
		assertNotNull(result);
		assertEquals("SELECT i2, i0 FROM syn123 ORDER BY \"i0\" DESC", result.getModel().toSql());
	}

	@Test
	public void testQueryPreflight_AdditionalQueryFilters() throws Exception {
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);

		Query query = new Query();
		query.setSql("select i2, i0 from "+tableId);

		ColumnSingleValueQueryFilter likeFilter = new ColumnSingleValueQueryFilter();
		likeFilter.setColumnName("i0");
		likeFilter.setOperator(ColumnSingleValueFilterOperator.LIKE);
		likeFilter.setValues(Arrays.asList("foo%"));
		query.setAdditionalFilters(Arrays.asList(likeFilter));

		Long maxBytesPerPage = null;

		// call under test
		SqlQuery result = manager.queryPreflight(user, query, maxBytesPerPage);
		assertNotNull(result);
		assertEquals("SELECT i2, i0 FROM syn123 WHERE ( \"i0\" LIKE 'foo%' )", result.getModel().toSql());
	}
	
	@Test
	public void testQueryPreflight_AdditionalQueryFiltersWithHasLike() throws Exception {
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);

		Query query = new Query();
		query.setSql("select i2, i0 from "+tableId);

		ColumnMultiValueFunctionQueryFilter likeFilter = new ColumnMultiValueFunctionQueryFilter();
		likeFilter.setColumnName("i12");
		likeFilter.setFunction(ColumnMultiValueFunction.HAS_LIKE);
		likeFilter.setValues(Arrays.asList("foo%", "bar"));
		query.setAdditionalFilters(Arrays.asList(likeFilter));

		Long maxBytesPerPage = null;

		// call under test
		SqlQuery result = manager.queryPreflight(user, query, maxBytesPerPage);
		assertNotNull(result);
		assertEquals("SELECT i2, i0 FROM syn123 WHERE ( \"i12\" HAS_LIKE ( 'foo%', 'bar' ) )", result.getModel().toSql());
	}
	
	@Test
	public void testQueryPreflight_AdditionalQueryFiltersWithHas() throws Exception {

		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);

		Query query = new Query();
		query.setSql("select i2, i0 from "+tableId);

		ColumnMultiValueFunctionQueryFilter likeFilter = new ColumnMultiValueFunctionQueryFilter();
		likeFilter.setColumnName("i12");
		likeFilter.setFunction(ColumnMultiValueFunction.HAS);
		likeFilter.setValues(Arrays.asList("foo%", "bar"));
		query.setAdditionalFilters(Arrays.asList(likeFilter));

		Long maxBytesPerPage = null;

		// call under test
		SqlQuery result = manager.queryPreflight(user, query, maxBytesPerPage);
		assertNotNull(result);
		assertEquals("SELECT i2, i0 FROM syn123 WHERE ( \"i12\" HAS ( 'foo%', 'bar' ) )", result.getModel().toSql());
		verify(mockTableManagerSupport).validateTableReadAccess(user, indexDescription);
	}
	
	@Test
	public void testQueryPreflightEmptySchema() throws Exception {
		// Return no columns
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn(0L);
		Query query = new Query();
		query.setSql("select * from "+tableId);
		Long maxBytesPerPage = null;
		EmptyResultException e = assertThrows(EmptyResultException.class, ()->{
			// call under test
			manager.queryPreflight(user, query, maxBytesPerPage);
		});
		assertEquals(tableId, e.getTableId());
	}
	
	@Test
	public void testQueryPreflightWithJoin() throws Exception {
		Query query = new Query();
		query.setSql("select * from syn123 join syn456");
		Long maxBytesPerPage = Long.MAX_VALUE;
		String message = assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.queryPreflight(user, query, maxBytesPerPage);
		}).getMessage();
		assertEquals(TableConstants.JOIN_NOT_SUPPORTED_IN_THIS_CONTEX_MESSAGE, message);
	}
	
	@Test
	public void testRunQueryDownloadAsStreamDownload() throws Exception{
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		setupNonExclusiveLock();
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		setupQueryCallback();
		
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql("select * from "+tableId);
		request.setSort(null);
		request.setIncludeRowIdAndRowVersion(false);
		request.setWriteHeader(true);

		// call under test
		DownloadFromTableResult results = manager.runQueryDownloadAsStream(
				mockProgressCallbackVoid, user, request, writer);
		assertNotNull(results);
		
		verify(mockTableManagerSupport).validateTableReadAccess(user, indexDescription);
		assertEquals(11, writtenLines.size());
	}
	
	@Test
	public void testRunQueryDownloadAsStreamDownloadDefaultValues() throws Exception{
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		setupNonExclusiveLock();
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		setupQueryCallback();
		
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql("select i0 from "+tableId);
		request.setSort(null);
		// null values should should have defaults set.
		request.setIncludeRowIdAndRowVersion(null);
		request.setWriteHeader(null);
		request.setIncludeEntityEtag(null);
		// row id and version numbers are needed for this case
		addRowIdAndVersionToRows();

		// call under test
		DownloadFromTableResult results = manager.runQueryDownloadAsStream(
				mockProgressCallbackVoid, user, request, writer);
		assertNotNull(results);
		
		// the header should be written and include rowid and version but not etag
		String line = Arrays.toString(writtenLines.get(0));
		assertEquals("[ROW_ID, ROW_VERSION, i0]", line);
		line = Arrays.toString(writtenLines.get(1));
		assertTrue(line.startsWith("[0, 101, string0"));
	}
	
	@Test
	public void testRunQueryDownloadAsStreamDownloadViewIncludeEtag() throws Exception{
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		setupNonExclusiveLock();
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockTableIndexDAO.getDistinctLongValues(idAndVersion, TableConstants.ROW_BENEFACTOR)).thenReturn(benfactors);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(new TableIndexDescription(idAndVersion));
		setupQueryCallback();
		
		IndexDescription indexDescription = new ViewIndexDescription(idAndVersion, EntityType.entityview);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql("select i0 from "+tableId);
		request.setSort(null);
		// null values should should have defaults set.
		request.setIncludeRowIdAndRowVersion(null);
		request.setWriteHeader(null);
		request.setIncludeEntityEtag(true);
		// row id and version numbers are needed for this case
		addRowIdAndVersionToRows();
		// need entity rows for this case
		convertRowsToEntityRows();

		// call under test
		DownloadFromTableResult results = manager.runQueryDownloadAsStream(
				mockProgressCallbackVoid, user, request, writer);
		assertNotNull(results);
		
		String line = Arrays.toString(writtenLines.get(0));
		assertEquals("[ROW_ID, ROW_VERSION, ROW_ETAG, i0]", line);
		line = Arrays.toString(writtenLines.get(1));
		assertTrue(line.startsWith("[0, 101, etag-0, string0"));
	}
	
	@Test
	public void testRunQueryDownloadAsStreamDownloadTableIncludeEtag() throws Exception{
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		setupNonExclusiveLock();
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);

		setupQueryCallback();
		
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql("select i0 from "+tableId);
		request.setSort(null);
		// null values should should have defaults set.
		request.setIncludeRowIdAndRowVersion(true);
		request.setWriteHeader(true);
		request.setIncludeEntityEtag(true);
		// row id and version numbers are needed for this case
		addRowIdAndVersionToRows();
		// need entity rows for this case
		convertRowsToEntityRows();

		// call under test
		DownloadFromTableResult results = manager.runQueryDownloadAsStream(
				mockProgressCallbackVoid, user, request, writer);
		assertNotNull(results);
		
		String line = Arrays.toString(writtenLines.get(0));
		assertEquals("[ROW_ID, ROW_VERSION, i0]", line);
		line = Arrays.toString(writtenLines.get(1));
		assertTrue(line.startsWith("[0, 101, string0"));
	}
	
	@Test
	public void testRunQueryDownloadAsStreamDownloadIncludeEtagWithoutRowId() throws Exception {
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		setupNonExclusiveLock();
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		setupQueryCallback();
		
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql("select i0 from "+tableId);
		request.setSort(null);
		// without rowId and version etag should not be written
		request.setIncludeRowIdAndRowVersion(false);
		request.setIncludeEntityEtag(true);
		// row id and version numbers are needed for this case
		addRowIdAndVersionToRows();
		// need entity rows for this case
		convertRowsToEntityRows();

		// call under test
		DownloadFromTableResult results = manager.runQueryDownloadAsStream(
				mockProgressCallbackVoid, user, request, writer);
		assertNotNull(results);
		
		String line = Arrays.toString(writtenLines.get(0));
		// just the selected value
		assertEquals("[i0]", line);
	}
	
	@Test
	public void testSetRequsetDefaultsQuery(){
		Query query = new Query();
		query.setIncludeEntityEtag(null);
		
		// call under test
		TableQueryManagerImpl.setDefaultsValues(query);
		assertFalse(query.getIncludeEntityEtag());
	}
	
	@Test
	public void testSetRequsetDefaultsDownloadFromTableRequest(){
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setIncludeRowIdAndRowVersion(null);
		request.setWriteHeader(null);
		request.setIncludeEntityEtag(null);
		
		// call under test
		TableQueryManagerImpl.setDefaultValues(request);
		assertTrue(request.getIncludeRowIdAndRowVersion());
		assertTrue(request.getWriteHeader());
		assertFalse(request.getIncludeEntityEtag());
	}
	
	@Test
	public void testRunQueryDownloadAsStreamEmptyDownload() throws NotFoundException, TableUnavailableException, TableFailedException, LockUnavilableException {
		// Return no columns
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn(0L);
		DownloadFromTableRequest request = new DownloadFromTableRequest();
		request.setSql("select * from "+tableId);
		request.setSort(null);
		request.setIncludeRowIdAndRowVersion(false);
		request.setWriteHeader(true);
		
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.runQueryDownloadAsStream(
					mockProgressCallbackVoid, user, request, writer);
		});
		
	}
	
	
	@Test
	public void testRunCountQuerySimpleAggregate() throws ParseException{
		SqlQuery query = new SqlQueryBuilder("select max(i0) from "+tableId, schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		long count = manager.runCountQuery(query, mockTableIndexDAO);
		assertEquals(1l, count);
		// no need to run a query for a simple aggregate
		verify(mockTableIndexDAO, never()).countQuery(anyString(), anyMap());
	}
	
	@Test
	public void testRunCountQueryNoPagination() throws ParseException{
		SqlQuery query = new SqlQueryBuilder("select i0 from "+tableId+" where i0 = 'aValue'", schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		ArgumentCaptor<String> sqlCaptrue = ArgumentCaptor.forClass(String.class);
		// setup the count returned from query
		when(mockTableIndexDAO.countQuery(sqlCaptrue.capture(), anyMap())).thenReturn(200L);
		// method under test
		long count = manager.runCountQuery(query, mockTableIndexDAO);
		assertEquals(200L, count);
		assertEquals("SELECT COUNT(*) FROM T123 WHERE _C0_ = :b0", sqlCaptrue.getValue());
		verify(mockTableIndexDAO).countQuery(anyString(), anyMap());
	}
	
	@Test
	public void testRunCountQueryWithLimitLessCount() throws ParseException{
		SqlQuery query = new SqlQueryBuilder("select i0 from "+tableId+" limit 100", schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		ArgumentCaptor<String> sqlCaptrue = ArgumentCaptor.forClass(String.class);
		// setup the count returned from query
		when(mockTableIndexDAO.countQuery(sqlCaptrue.capture(), anyMap())).thenReturn(200L);
		// method under test
		long count = manager.runCountQuery(query, mockTableIndexDAO);
		assertEquals(100L, count);
	}
	
	@Test
	public void testRunCountQueryWithLimitMoreCount() throws ParseException{
		SqlQuery query = new SqlQueryBuilder("select i0 from "+tableId+" limit 300", schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		ArgumentCaptor<String> sqlCaptrue = ArgumentCaptor.forClass(String.class);
		// setup the count returned from query
		when(mockTableIndexDAO.countQuery(sqlCaptrue.capture(), anyMap())).thenReturn(200L);
		// method under test
		long count = manager.runCountQuery(query, mockTableIndexDAO);
		assertEquals(200L, count);
	}
		
	@Test
	public void testRunCountQueryWithLimitAndOffsetLessThanCount() throws ParseException{
		SqlQuery query = new SqlQueryBuilder("select i0 from "+tableId+" limit 100 offset 50", schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		ArgumentCaptor<String> sqlCaptrue = ArgumentCaptor.forClass(String.class);
		// setup the count returned from query
		when(mockTableIndexDAO.countQuery(sqlCaptrue.capture(), anyMap())).thenReturn(200L);
		// method under test
		long count = manager.runCountQuery(query, mockTableIndexDAO);
		assertEquals(100L, count);
	}
	
	@Test
	public void testRunCountQueryWithLimitAndOffsetMoreThanCount() throws ParseException{
		SqlQuery query = new SqlQueryBuilder("select i0 from "+tableId+" limit 100 offset 150", schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		ArgumentCaptor<String> sqlCaptrue = ArgumentCaptor.forClass(String.class);
		// setup the count returned from query
		when(mockTableIndexDAO.countQuery(sqlCaptrue.capture(), anyMap())).thenReturn(200L);
		// method under test
		long count = manager.runCountQuery(query, mockTableIndexDAO);
		assertEquals(50L, count);
	}
	
	@Test
	public void testRunCountQueryWithCountLessThanOffset() throws ParseException{
		SqlQuery query = new SqlQueryBuilder("select i0 from "+tableId+" limit 100 offset 150", schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		ArgumentCaptor<String> sqlCaptrue = ArgumentCaptor.forClass(String.class);
		// setup the count returned from query
		when(mockTableIndexDAO.countQuery(sqlCaptrue.capture(), anyMap())).thenReturn(149L);
		// method under test
		long count = manager.runCountQuery(query, mockTableIndexDAO);
		assertEquals(0L, count);
	}
	
	/**
	 * The group by references an 'AS' value from the select.  The
	 * resulting count(distinct) must use a direct reference not the 'AS' value.
	 * @throws ParseException
	 */
	@Test
	public void testRunCountQueryPLFM_3899() throws ParseException{
		SqlQuery query = new SqlQueryBuilder("select i0 as bar from "+tableId+" group by bar", schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		ArgumentCaptor<String> sqlCaptrue = ArgumentCaptor.forClass(String.class);
		// setup the count returned from query
		when(mockTableIndexDAO.countQuery(sqlCaptrue.capture(), anyMap())).thenReturn(200L);
		// method under test
		long count = manager.runCountQuery(query, mockTableIndexDAO);
		assertEquals("SELECT COUNT(DISTINCT _C0_) FROM T123", sqlCaptrue.getValue());
	}
	
	/**
	 * When a distinct query is converted to a count query, any 'AS' clause
	 * from the original SQL must be excluded in the resulting count(distinct).
	 * @throws ParseException
	 */
	@Test
	public void testRunCountQueryPLFM_3900() throws ParseException{
		SqlQuery query = new SqlQueryBuilder("select distinct i0 as bar, i4 from "+tableId, schemaProvider, user.getId()).indexDescription(new TableIndexDescription(idAndVersion)).build();
		ArgumentCaptor<String> sqlCaptrue = ArgumentCaptor.forClass(String.class);
		// setup the count returned from query
		when(mockTableIndexDAO.countQuery(sqlCaptrue.capture(), anyMap())).thenReturn(200L);
		// method under test
		long count = manager.runCountQuery(query, mockTableIndexDAO);
		assertEquals("SELECT COUNT(DISTINCT _C0_, _C4_) FROM T123", sqlCaptrue.getValue());
	}
	
	@Test
	public void testIsRowCountEqualToMaxRowsPerPage(){
		QueryResultBundle bundle = null;
		int maxNumberRows = 0;
		assertFalse(TableQueryManagerImpl.isRowCountEqualToMaxRowsPerPage(bundle, maxNumberRows));
		bundle = new QueryResultBundle();
		assertFalse(TableQueryManagerImpl.isRowCountEqualToMaxRowsPerPage(bundle, maxNumberRows));
		bundle.setQueryResult(new QueryResult());
		assertFalse(TableQueryManagerImpl.isRowCountEqualToMaxRowsPerPage(bundle, maxNumberRows));
		bundle.getQueryResult().setQueryResults(new RowSet());
		assertFalse(TableQueryManagerImpl.isRowCountEqualToMaxRowsPerPage(bundle, maxNumberRows));
		Row row = new Row();
		bundle.getQueryResult().getQueryResults().setRows(Lists.newArrayList(row));
		assertFalse(TableQueryManagerImpl.isRowCountEqualToMaxRowsPerPage(bundle, maxNumberRows));
		maxNumberRows = 1;
		assertTrue(TableQueryManagerImpl.isRowCountEqualToMaxRowsPerPage(bundle, maxNumberRows));
		maxNumberRows = 2;
		assertFalse(TableQueryManagerImpl.isRowCountEqualToMaxRowsPerPage(bundle, maxNumberRows));
	}
	
	@Test
	public void testQuerySinglePageWithNextPage() throws Exception{
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		setupNonExclusiveLock();
		when(mockTableIndexDAO.countQuery(anyString(), anyMap())).thenReturn(10L);
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		setupQueryCallback();
		
		// setup the results to return one row.
		Row row = rows.get(0);
		rows.clear();
		rows.add(row);
		queryOptions = new QueryOptions().withRunQuery(true).withRunCount(true).withReturnFacets(false).withReturnMaxRowsPerPage(true);
		Query query = new Query();
		query.setSql("select * from "+tableId);
		query.setSort(sortList);
		query.setLimit(null);
		query.setOffset(null);
		manager.setMaxBytesPerRequest(1);
		// call under test.
		QueryResultBundle result = manager.querySinglePage(
				mockProgressCallbackVoid, user, query, queryOptions);
		
		assertNotNull(result);
		assertEquals(new Long(1), result.getMaxRowsPerPage());
		assertNotNull(result.getQueryResult());
		assertNotNull(result.getQueryResult().getNextPageToken());
		Query nextQuery = TableQueryUtils.createQueryFromNextPageToken(result.getQueryResult().getNextPageToken());
		assertNotNull(nextQuery);
		assertEquals(null, nextQuery.getLimit());
		assertEquals(new Long(1),nextQuery.getOffset());
		assertEquals(query.getSql(), nextQuery.getSql());
	}
	
	@Test
	public void testQuerySinglePageWithNoOptions() throws Exception{
		// setup the results to return one row.
		Row row = rows.get(0);
		rows.clear();
		rows.add(row);
		// no options
		queryOptions = new QueryOptions();
		Query query = new Query();
		query.setSql("select * from "+tableId);
		query.setSort(sortList);
		query.setLimit(null);
		query.setOffset(null);
		manager.setMaxBytesPerRequest(1);
		// call under test.
		QueryResultBundle result = manager.querySinglePage(
				mockProgressCallbackVoid, user, query, queryOptions);
		
		assertNotNull(result);
		assertNull(result.getMaxRowsPerPage());
		assertNull(result.getQueryResult());
		assertNull(result.getMaxRowsPerPage());
	}
	
	@Test
	public void testQuerySinglePageWithNoNextPage() throws Exception{		
		queryOptions = new QueryOptions().withRunQuery(true).withRunCount(true).withReturnFacets(false);
		Query query = new Query();
		query.setSql("select * from "+tableId);
		query.setSort(sortList);
		query.setLimit(null);
		query.setOffset(null);
		// set to not limit the number of rows.
		manager.setMaxBytesPerRequest(Integer.MAX_VALUE);
		// call under test.
		QueryResultBundle result = manager.querySinglePage(
				mockProgressCallbackVoid, user, query, queryOptions);
		
		assertNotNull(result);
		assertNull(result.getMaxRowsPerPage());
		assertNotNull(result.getQueryResult());
		assertNull(result.getQueryResult().getNextPageToken());
	}
	
	@Test
	public void testQuerySinglePageWithEtag() throws Exception {
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		setupNonExclusiveLock();
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		setupQueryCallback();
		
		addRowIdAndVersionToRows();
		convertRowsToEntityRows();
		
		queryOptions = new QueryOptions().withRunQuery(true).withRunCount(false).withReturnFacets(false);
		Query query = new Query();
		query.setSql("select * from "+tableId);
		query.setIncludeEntityEtag(true);
		
		// call under test.
		QueryResultBundle result = manager.querySinglePage(
				mockProgressCallbackVoid, user, query, queryOptions);
		assertNotNull(result);
		assertNotNull(result.getQueryResult());
		assertNotNull(result.getQueryResult().getQueryResults());
		List<Row> rows = result.getQueryResult().getQueryResults().getRows();
		assertNotNull(rows);
		Row row = rows.get(0);
		assertNotNull(row.getEtag());
	}
	
	@Test
	public void testQuerySinglePageRunQueryTrue() throws Exception{		
		queryOptions = new QueryOptions().withRunQuery(true).withRunCount(true).withReturnFacets(false);
		Query query = new Query();
		query.setSql("select * from "+tableId);
		query.setSort(sortList);
		query.setLimit(null);
		query.setOffset(null);
		// call under test.
		QueryResultBundle result = manager.querySinglePage(
				mockProgressCallbackVoid, user, query, queryOptions);
		
		assertNotNull(result);
		assertNotNull(result.getQueryResult());
		assertNotNull(result.getQueryResult().getQueryResults());
		List<Row> rows = result.getQueryResult().getQueryResults().getRows();
		assertNotNull(rows);
		assertEquals(rows, rows);
	}
	
	@Test
	public void testQuerySinglePageRunQueryFalse() throws Exception{		
		queryOptions = new QueryOptions().withRunQuery(false).withRunCount(true).withReturnFacets(false);
		Query query = new Query();
		query.setSql("select * from "+tableId);
		query.setSort(sortList);
		query.setLimit(null);
		query.setOffset(null);
		// call under test.
		QueryResultBundle result = manager.querySinglePage(
				mockProgressCallbackVoid, user, query, queryOptions);
		
		assertNotNull(result);
		// there should be no query results.
		assertNull(result.getQueryResult());
	}
	
	/**
	 * Override of the limit should not change the total count.
	 */
	@Test
	public void testQuerySinglePageOverrideLimit() throws Exception{
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		setupNonExclusiveLock();
		when(mockTableIndexDAO.countQuery(anyString(), anyMap())).thenReturn(10L);
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		
		Long totalCount = 101L;
		when(mockTableIndexDAO.countQuery(anyString(), anyMap())).thenReturn(totalCount);
		queryOptions = new QueryOptions().withRunQuery(false).withRunCount(true).withReturnFacets(false);
		Query query = new Query();
		query.setSql("select * from "+tableId);
		query.setSort(sortList);
		query.setLimit(11L);
		query.setOffset(10L);
		// call under test.
		QueryResultBundle result = manager.querySinglePage(
				mockProgressCallbackVoid, user, query, queryOptions);		
		assertNotNull(result);
		// there should be no query results.
		assertNull(result.getQueryResult());
		assertEquals(totalCount, result.getQueryCount());
	}
	
	/**
	 * When the query includes a limit, that limit will change the count.
	 * 
	 * @throws Exception
	 */
	@Test
	public void testQuerySinglePageWithLimit() throws Exception{
		when(mockTableManagerSupport.getTableStatusOrCreateIfNotExists(idAndVersion)).thenReturn(status);
		setupNonExclusiveLock();
		when(mockTableIndexDAO.countQuery(anyString(), anyMap())).thenReturn(10L);
		when(mockTableManagerSupport.getTableSchemaCount(any())).thenReturn((long)models.size());
		when(mockTableManagerSupport.getTableSchema(idAndVersion)).thenReturn(models);
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		when(mockTableManagerSupport.getIndexDescription(any())).thenReturn(indexDescription);
		
		Long totalCount = 101L;
		when(mockTableIndexDAO.countQuery(anyString(), anyMap())).thenReturn(totalCount);
		queryOptions = new QueryOptions().withRunQuery(false).withRunCount(true).withReturnFacets(false);
		Query query = new Query();
		query.setSql("select * from "+tableId+" limit 11");
		query.setSort(sortList);
		query.setLimit(11L);
		query.setOffset(10L);
		// call under test.
		QueryResultBundle result = manager.querySinglePage(
				mockProgressCallbackVoid, user, query, queryOptions);	
		
		assertNotNull(result);
		// there should be no query results.
		assertNull(result.getQueryResult());
		assertEquals(new Long(11), result.getQueryCount());
	}
	
	@Test
	public void testBuildBenefactorFilter() throws ParseException, EmptyResultException{
		QuerySpecification query = new TableQueryParser("select i0 from "+tableId+" where i1 is not null").querySpecification();
		LinkedHashSet<Long> benefactorIds = new LinkedHashSet<Long>();
		benefactorIds.add(456L);
		benefactorIds.add(123L);
		
		// call under test
		QuerySpecification filtered = TableQueryManagerImpl.buildBenefactorFilter(query, benefactorIds);
		assertNotNull(filtered);
		// should filter by benefactorId
		assertEquals("SELECT i0 FROM syn123 WHERE ( i1 IS NOT NULL ) AND ROW_BENEFACTOR IN ( 456, 123 )", filtered.toSql());
	}
	
	@Test
	public void testBuildBenefactorFilterNoBenefactorInSchema() throws ParseException, EmptyResultException{
		// there is no benefactor column in the schema.
		QuerySpecification query = new TableQueryParser("select i0 from "+tableId+" where i1 is not null").querySpecification();
		LinkedHashSet<Long> benefactorIds = new LinkedHashSet<Long>();
		benefactorIds.add(456L);
		benefactorIds.add(123L);
		
		// call under test
		QuerySpecification filtered = TableQueryManagerImpl.buildBenefactorFilter(query, benefactorIds);
		assertNotNull(filtered);
		// should filter by benefactorId
		assertEquals("SELECT i0 FROM syn123 WHERE ( i1 IS NOT NULL ) AND ROW_BENEFACTOR IN ( 456, 123 )", filtered.toSql());
	}
	
	/**
	 * 
	 * PLFM-4036 identified that the benefactor search condition would limit the row visibility to
	 * the caller by appending 'AND <BENEFACTOR_FILTER> to a user's existing query. Therefore, if
	 * the user's original query contained at least two search conditions separated by an 'OR', either
	 * of the original conditions could negate the benefactor filter.
	 * 
	 * The fix was to unconditionally add the filter benefactor to the query such as:
	 * WHERE ( <USER_CONDITION_1> OR <USER_CONDITION_2> ) AND <BENEFACTOR_FILTER>
	 * @throws ParseException
	 * @throws EmptyResultException
	 */
	@Test
	public void testBuildBenefactorFilterPLFM_4036() throws ParseException, EmptyResultException {	
		QuerySpecification query = new TableQueryParser("select i0 from "+tableId+" where i1 > 0 or i1 is not null").querySpecification();
		LinkedHashSet<Long> benefactorIds = new LinkedHashSet<Long>();
		benefactorIds.add(456L);
		benefactorIds.add(123L);
		
		// call under test
		QuerySpecification filtered = TableQueryManagerImpl.buildBenefactorFilter(query, benefactorIds);
		assertNotNull(filtered);
		// should filter by benefactorId
		assertEquals("SELECT i0 FROM syn123 WHERE ( i1 > 0 OR i1 IS NOT NULL ) AND ROW_BENEFACTOR IN ( 456, 123 )", filtered.toSql());
	}
	
	@Test
	public void testBuildBenefactorFilterNoWhere() throws ParseException, EmptyResultException{
		// no where clause in the original query.
		QuerySpecification query = new TableQueryParser("select i0 from "+tableId).querySpecification();
		LinkedHashSet<Long> benefactorIds = new LinkedHashSet<Long>();
		benefactorIds.add(123L);
		// call under test
		QuerySpecification filtered = TableQueryManagerImpl.buildBenefactorFilter(query, benefactorIds);
		assertNotNull(filtered);
		// should filter by benefactorId
		assertEquals("SELECT i0 FROM syn123 WHERE ROW_BENEFACTOR IN ( 123 )", filtered.toSql());
	}

	@Test
	public void testBuildBenefactorFilterEmpty() throws ParseException, EmptyResultException{
		QuerySpecification query = new TableQueryParser("select i0 from "+tableId+" where i1 is not null").querySpecification();
		LinkedHashSet<Long> benefactorIds = new LinkedHashSet<Long>();
		// call under test
		QuerySpecification filtered = TableQueryManagerImpl.buildBenefactorFilter(query, benefactorIds);
		assertNotNull(filtered);

		// should make filter always evaluate to false
		assertEquals("SELECT i0 FROM syn123 WHERE ( i1 IS NOT NULL ) AND ROW_BENEFACTOR IN ( -1 )", filtered.toSql());

	}
	
	@Test
	public void testAddRowLevelFilterEmpty() throws Exception {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockTableIndexDAO.getDistinctLongValues(idAndVersion, TableConstants.ROW_BENEFACTOR)).thenReturn(benfactors);
		IndexDescription indexDescription = new ViewIndexDescription(idAndVersion, EntityType.entityview);
		QuerySpecification query = new TableQueryParser("select i0 from "+tableId).querySpecification();
		//return empty benefactors
		when(mockTableIndexDAO.getDistinctLongValues(idAndVersion, TableConstants.ROW_BENEFACTOR)).thenReturn(new HashSet<Long>());
		// call under test
		QuerySpecification result = manager.addRowLevelFilter(user, query, indexDescription);
		assertNotNull(result);
		assertEquals("SELECT i0 FROM syn123 WHERE ROW_BENEFACTOR IN ( -1 )", result.toSql());
	}
	
	@Test
	public void testAddRowLevelFilterWithJoin() throws Exception {
		QuerySpecification query = new TableQueryParser("select * from syn123 join syn456").querySpecification();
		String message = assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.addRowLevelFilter(user, query, new TableIndexDescription(idAndVersion));
		}).getMessage();
		assertEquals(TableConstants.JOIN_NOT_SUPPORTED_IN_THIS_CONTEX_MESSAGE, message);
	}

	@Test
	public void getAddRowLevelFilterTableDoesNotExist() throws Exception {
		when(mockTableConnectionFactory.getConnection(idAndVersion)).thenReturn(mockTableIndexDAO);
		when(mockTableIndexDAO.getDistinctLongValues(idAndVersion, TableConstants.ROW_BENEFACTOR)).thenReturn(benfactors);
		
		QuerySpecification query = new TableQueryParser("select i0 from "+tableId).querySpecification();
		//return empty benefactors
		when(mockTableIndexDAO.getDistinctLongValues(idAndVersion, TableConstants.ROW_BENEFACTOR)).thenThrow(BadSqlGrammarException.class);
		IndexDescription indexDescription = new ViewIndexDescription(idAndVersion, EntityType.entityview);
		
		// call under test
		QuerySpecification result = manager.addRowLevelFilter(user, query, indexDescription);

		//Throw table not existing should be treated same as not having benefactors.
		assertNotNull(result);
		assertEquals("SELECT i0 FROM syn123 WHERE ROW_BENEFACTOR IN ( -1 )", result.toSql());
	}
	
	@Test
	public void testAddRowLevelFilter() throws Exception {
		when(mockTableConnectionFactory.getConnection(any())).thenReturn(mockTableIndexDAO);
		when(mockTableIndexDAO.getDistinctLongValues(any(), any())).thenReturn(benfactors);
		when(mockTableManagerSupport.getAccessibleBenefactors(any(), any(), any())).thenReturn(subSet);
		IndexDescription indexDescription = new ViewIndexDescription(idAndVersion, EntityType.entityview);
		
		QuerySpecification query = new TableQueryParser("select i0 from "+tableId).querySpecification();
		// call under test
		QuerySpecification result = manager.addRowLevelFilter(user, query, indexDescription);
		assertNotNull(result);
		assertEquals("SELECT i0 FROM syn123 WHERE ROW_BENEFACTOR IN ( 444 )", result.toSql());
		verify(mockTableIndexDAO).getDistinctLongValues(idAndVersion, TableConstants.ROW_BENEFACTOR);
		verify(mockTableManagerSupport).getAccessibleBenefactors(user, ObjectType.ENTITY, benfactors);
	}
	
	@Test
	public void testAddRowLevelFilterWithTable() throws Exception {
		IndexDescription indexDescription = new TableIndexDescription(idAndVersion);
		
		QuerySpecification query = new TableQueryParser("select i0 from "+tableId).querySpecification();
		// call under test
		QuerySpecification result = manager.addRowLevelFilter(user, query, indexDescription);
		assertNotNull(result);
		assertEquals("SELECT i0 FROM syn123", result.toSql());
		verify(mockTableIndexDAO, never()).getDistinctLongValues(any(), any());
		verify(mockTableManagerSupport, never()).getAccessibleBenefactors(any(), any(), any());
	}
	
	
	////////////////////////////
	// runFacetQueries() Tests
	////////////////////////////
	@Test
	public void testRunFacetQueriesNullFacetModel(){
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.runFacetQueries(null, mockTableIndexDAO);
		});
	}
	
	@Test
	public void testRunFacetQueriesFacetColumns(){
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.runFacetQueries(Mockito.mock(FacetModel.class), null);
		});
	}	
	
	@Test
	public void testRunFacetQueries(){
		//setup
		FacetModel mockFacetModel = Mockito.mock(FacetModel.class);
		FacetTransformer mockTransformer1 = Mockito.mock(FacetTransformerValueCounts.class);
		FacetTransformer mockTransformer2 = Mockito.mock(FacetTransformerRange.class);
		SqlQuery mockSql1 = Mockito.mock(SqlQuery.class);
		SqlQuery mockSql2 = Mockito.mock(SqlQuery.class);
		RowSet rs1 = new RowSet();
		RowSet rs2 = new RowSet();
		FacetColumnResultValues result1 = new FacetColumnResultValues();
		FacetColumnResultRange result2 = new FacetColumnResultRange();
		
		
		when(mockTransformer1.getFacetSqlQuery()).thenReturn(mockSql1);
		when(mockTransformer2.getFacetSqlQuery()).thenReturn(mockSql2);
		when(mockTableIndexDAO.query(null, mockSql1)).thenReturn(rs1);
		when(mockTableIndexDAO.query(null, mockSql2)).thenReturn(rs2);
		when(mockTransformer1.translateToResult(rs1)).thenReturn(result1);
		when(mockTransformer2.translateToResult(rs2)).thenReturn(result2);
		List<FacetTransformer> transformersList = Arrays.asList(mockTransformer1, mockTransformer2);
		when(mockFacetModel.getFacetInformationQueries()).thenReturn(transformersList);
		
		//call method
		List<FacetColumnResult> results = manager.runFacetQueries(mockFacetModel, mockTableIndexDAO);
		
		//verify and assert
		verify(mockFacetModel).getFacetInformationQueries();
		verify(mockTransformer1).getFacetSqlQuery();
		verify(mockTransformer2).getFacetSqlQuery();
		verify(mockTableIndexDAO).query(null, mockSql1);
		verify(mockTableIndexDAO).query(null, mockSql2);
		verify(mockTransformer1).translateToResult(rs1);
		verify(mockTransformer2).translateToResult(rs2);
		
		
		assertEquals(2, results.size());
		assertEquals(result1, results.get(0));
		assertEquals(result2, results.get(1));
		
		verifyNoMoreInteractions(mockTableIndexDAO, mockFacetModel,mockTransformer1, mockTransformer2);

	}
	
	@Test
	public void testRunSumFileSize() throws Exception {
		List<IdAndVersion> idAndVersionList = Arrays.asList(
				IdAndVersion.parse("1.1"),
				IdAndVersion.parse("2.1")
		);
		
		when(mockTableIndexDAO.getRowIdAndVersions(any(), any())).thenReturn(idAndVersionList);
		
		when(mockTableIndexDAO.getSumOfFileSizes(any(), any())).thenReturn(sumFilesizes);
		
		// query against an entity view.
		SqlQuery query = new SqlQueryBuilder("select i0 from " + tableId + " limit 1000", schemaProvider, user.getId())
				.indexDescription(new ViewIndexDescription(idAndVersion, EntityType.entityview)).build();
		
		ArgumentCaptor<String> sqlCapture = ArgumentCaptor.forClass(String.class);

		// call under test
		SumFileSizes sum = manager.runSumFileSize(query, mockTableIndexDAO);
		assertNotNull(sum);
		assertEquals(sumFilesizes, sum.getSumFileSizesBytes());
		assertFalse(sum.getGreaterThan());
		verify(mockTableIndexDAO).getRowIdAndVersions(sqlCapture.capture(), any());
		assertEquals("SELECT ROW_ID, ROW_VERSION FROM T123 LIMIT 101", sqlCapture.getValue());
		verify(mockTableIndexDAO).getSumOfFileSizes(ViewObjectType.ENTITY.getMainType(), idAndVersionList);
	}
	
	@Test
	public void testRunSumFileSizeForDataset() throws Exception {
		List<IdAndVersion> idAndVersionList = Arrays.asList(
				IdAndVersion.parse("1.1"),
				IdAndVersion.parse("2.1")
		);
		
		when(mockTableIndexDAO.getRowIdAndVersions(any(), any())).thenReturn(idAndVersionList);
		
		when(mockTableIndexDAO.getSumOfFileSizes(any(), any())).thenReturn(sumFilesizes);
		
		// query against an entity view.
		SqlQuery query = new SqlQueryBuilder("select i0 from " + tableId + " limit 1000", schemaProvider, user.getId())
				.indexDescription(new ViewIndexDescription(idAndVersion, EntityType.dataset)).build();
		
		ArgumentCaptor<String> sqlCapture = ArgumentCaptor.forClass(String.class);

		// call under test
		SumFileSizes sum = manager.runSumFileSize(query, mockTableIndexDAO);
		assertNotNull(sum);
		assertEquals(sumFilesizes, sum.getSumFileSizesBytes());
		assertFalse(sum.getGreaterThan());
		verify(mockTableIndexDAO).getRowIdAndVersions(sqlCapture.capture(), any());
		assertEquals("SELECT ROW_ID, ROW_VERSION FROM T123 LIMIT 101", sqlCapture.getValue());
		verify(mockTableIndexDAO).getSumOfFileSizes(ViewObjectType.ENTITY.getMainType(), idAndVersionList);
	}
	
	@Test
	public void testRunSumFileSizeOverLimit() throws Exception {
		List<IdAndVersion> idAndVersionList = new ArrayList<>();

		// setup a result with more than the max rows
		for (int i=0; i< TableQueryManagerImpl.MAX_ROWS_PER_CALL + 1L; i++) {
			idAndVersionList.add(IdAndVersion.newBuilder().setId(new Long(i)).setVersion(1L).build());
		}
		
		when(mockTableIndexDAO.getRowIdAndVersions(any(), any())).thenReturn(idAndVersionList);
		when(mockTableIndexDAO.getSumOfFileSizes(any(), any())).thenReturn(sumFilesizes);
		
		// query against an entity view.
		SqlQuery query = new SqlQueryBuilder("select i0 from " + tableId, schemaProvider, user.getId())
				.indexDescription(new ViewIndexDescription(idAndVersion, EntityType.entityview)).build();

		// call under test
		SumFileSizes sum = manager.runSumFileSize(query, mockTableIndexDAO);
		assertNotNull(sum);
		assertEquals(sumFilesizes, sum.getSumFileSizesBytes());
		// when over the limit
		assertTrue(sum.getGreaterThan());
		verify(mockTableIndexDAO).getRowIdAndVersions(anyString(), any());
		verify(mockTableIndexDAO).getSumOfFileSizes(ViewObjectType.ENTITY.getMainType(), idAndVersionList);
	}

	
	@Test
	public void testRunSumFileSizeNonEntityViewOrDataset() throws Exception {
		// query against an entity view.
		SqlQuery query = new SqlQueryBuilder("select i0 from " + tableId, schemaProvider, user.getId())
				.indexDescription(new TableIndexDescription(idAndVersion)).indexDescription(new TableIndexDescription(idAndVersion)).build();
		// call under test
		SumFileSizes sum = manager.runSumFileSize(query, mockTableIndexDAO);
		assertNotNull(sum);
		assertEquals(new Long(0), sum.getSumFileSizesBytes());
		assertFalse(sum.getGreaterThan());
		verify(mockTableIndexDAO, never()).getRowIdAndVersions(anyString(), any());
		verify(mockTableIndexDAO, never()).getSumOfFileSizes(any(), any());
	}
	
	@Test
	public void testRunSumFileSizeAggregate() throws Exception {
		SqlQuery query = new SqlQueryBuilder("select count(*) from " + tableId, schemaProvider, user.getId())
				.indexDescription(new ViewIndexDescription(idAndVersion, EntityType.entityview)).build();
		// call under test
		SumFileSizes sum = manager.runSumFileSize(query, mockTableIndexDAO);
		assertNotNull(sum);
		assertEquals(new Long(0), sum.getSumFileSizesBytes());
		assertFalse(sum.getGreaterThan());
		verify(mockTableIndexDAO, never()).getRowIdAndVersions(anyString(), any());
		verify(mockTableIndexDAO, never()).getSumOfFileSizes(eq(ViewObjectType.ENTITY.getMainType()), any());
	}
	
	private RowSet createRowSetForTest(List<String> headerNames, List<String>... rowValues){
		RowSet rowSet = new RowSet();
		List<SelectColumn> headerObjects = new ArrayList<>();
		
		//select column for first row
		for(String headerName: headerNames){
			SelectColumn headerObj = new SelectColumn();
			headerObj.setName(headerName);
			headerObjects.add(headerObj);
		}
		rowSet.setHeaders(headerObjects);
		rowSet.setRows(new ArrayList<Row>());
		
		List<Row> rows = new ArrayList<Row>();
		for(List<String> rowValue : rowValues){
			Row row = new Row();
			row.setValues(rowValue);
			rows.add(row);
		}
		rowSet.setRows(rows);
		return rowSet;
	}
}

