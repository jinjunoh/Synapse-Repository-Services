package org.sagebionetworks.table.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.model.dao.table.TableType;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.TableConstants;
import org.sagebionetworks.table.cluster.columntranslation.ColumnTranslationReference;
import org.sagebionetworks.table.query.ParseException;
import org.sagebionetworks.table.query.TableQueryParser;
import org.sagebionetworks.table.query.model.ColumnReference;
import org.sagebionetworks.table.query.model.OrderByClause;
import org.sagebionetworks.table.query.model.QuerySpecification;
import org.sagebionetworks.table.query.model.SelectList;
import org.sagebionetworks.table.query.model.TableNameCorrelation;
import org.sagebionetworks.table.query.util.SqlElementUtils;
import org.sagebionetworks.util.Pair;
import org.sagebionetworks.util.ValidateArgument;

/**
 * Immutable mapping for tables and columns from a query..
 *
 */
public class TableAndColumnMapper implements ColumnLookup {

	private final List<TableInfo> tables;
	private final SchemaProvider schemaProvider;

	public TableAndColumnMapper(QuerySpecification query, SchemaProvider schemaProvider) {
		ValidateArgument.required(query, "QuerySpecification");
		ValidateArgument.required(schemaProvider, "SchemaProvider");
		this.schemaProvider = schemaProvider;
		List<TableInfo> tables = new ArrayList<TableInfo>();
		int tableIndex = 0;
		// extract all of the table information from the SQL model.
		for (TableNameCorrelation table : query.createIterable(TableNameCorrelation.class)) {
			IdAndVersion id = IdAndVersion.parse(table.getTableName().toSql());
			List<ColumnModel> schema = schemaProvider.getTableSchema(id);
			if (schema.isEmpty()) {
				throw new IllegalArgumentException(String.format("Schema for %s is empty.", id));
			}
			TableInfo tableInfo = new TableInfo(table, tableIndex++, schema);
			tables.add(tableInfo);
		}
		this.tables = Collections.unmodifiableList(tables);
	}

	/**
	 * Get all of the table IDs referenced by this query.
	 * 
	 * @return
	 */
	public List<IdAndVersion> getTableIds() {
		return tables.stream().map(t -> t.getTableIdAndVersion()).collect(Collectors.toList());
	}
	
	/**
	 * Get the single IdAndVersion for the given query.  If this query includes more than one
	 * table an Optional.empty() will be returned.
	 * @return
	 */
	public Optional<IdAndVersion> getSingleTableId() {
		if(tables.size() == 1) {
			return Optional.of(tables.get(0).getTableIdAndVersion());
		}else {
			return Optional.empty();
		}
		
	}

	/**
	 * Get the union of the schemas for each table referenced in the query.
	 * 
	 * @return
	 */
	public List<ColumnModel> getUnionOfAllTableSchemas() {
		return tables.stream().flatMap(t -> t.getTableSchema().stream()).collect(Collectors.toList());
	}

	/**
	 * Build a full SelectList from all columns from all tables.
	 * 
	 * @return
	 * @throws ParseException
	 */
	public SelectList buildSelectAllColumns() {
		StringJoiner sqlJoiner = new StringJoiner(", ");
		for (TableInfo tableInfo : tables) {
			for (ColumnModel column : tableInfo.getTableSchema()) {
				StringBuilder sql = new StringBuilder();
				if (tableInfo.getTableAlias().isPresent()) {
					sql.append(tableInfo.getTableAlias().get());
					sql.append(".");
				} else if (tables.size() > 1) {
					sql.append(tableInfo.getOriginalTableName());
					sql.append(".");
				}
				sql.append(SqlElementUtils.wrapInDoubleQuotes(column.getName()));
				sqlJoiner.add(sql.toString());
			}
		}
		try {
			return new TableQueryParser(sqlJoiner.toString()).selectList();
		} catch (ParseException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Builds the SelectList and OrderByClause to fetch the file ids in a given query. If the fileColumnId is supplied will attempt to use the
	 * given column, otherwise attempts to use the ROW_ID if the table type referenced by the model is a view or a dataset.
	 * 
	 * @param fileColumnId
	 * @return
	 * @throws ParseException
	 */
	public Pair<SelectList, OrderByClause> buildSelectAndOrderByFileColumn(Long fileColumnId) throws ParseException {
		IdAndVersion idAndVersion = getSingleTableId().orElseThrow(TableConstants.JOIN_NOT_SUPPORTED_IN_THIS_CONTEXT);		
		TableType tableType = schemaProvider.getTableType(idAndVersion);
		
		String selectFileColumnName = getSelectFileColumnName(tableType, fileColumnId);
		
		SelectList selectList = new TableQueryParser(selectFileColumnName).selectList();
		OrderByClause orderBy = new OrderByClause(new TableQueryParser(selectFileColumnName).sortSpecificationList());
		
		return new Pair<>(selectList, orderBy);
	}	
	
	public Pair<SelectList, OrderByClause> buildSelectAndOrderByFileAndVersionColumn(Long fileColumnId, Long fileVersionColumnId) throws ParseException {
		IdAndVersion idAndVersion = getSingleTableId().orElseThrow(TableConstants.JOIN_NOT_SUPPORTED_IN_THIS_CONTEXT);
		TableType tableType = schemaProvider.getTableType(idAndVersion);
		
		String selectFileColumnName = getSelectFileColumnName(tableType, fileColumnId);
		
		String selectFileVersionColumnName;
		
		if (fileVersionColumnId == null) {
			// If the column id is not supplied we can infer the file column version only for a view or dataset
			if (!TableType.entityview.equals(tableType) && !TableType.dataset.equals(tableType)) {
				throw new IllegalArgumentException(String.format("'%s' is not a file view or a dataset, the query.selectFileVersionColumn must be specified", idAndVersion.toString()));
			}
			
			selectFileVersionColumnName = TableConstants.ROW_VERSION;
		} else {
			selectFileVersionColumnName = getUnionOfAllTableSchemas().stream()
					.filter(selectColumn -> selectColumn.getId().equals(fileVersionColumnId.toString()) && ColumnType.INTEGER == selectColumn.getColumnType())
					.findFirst()
					.map(ColumnModel::getName)
					.orElseThrow(() -> new IllegalArgumentException("The query.selectFileVersionColumn must be an INTEGER column that is part of the schema of the underlying table/view"));
		}
		
		selectFileVersionColumnName = SqlElementUtils.wrapInDoubleQuotes(selectFileVersionColumnName);
		
		String selectedColumns = String.join(",", selectFileColumnName, selectFileVersionColumnName);
		
		SelectList selectList = new TableQueryParser(selectedColumns).selectList();
		OrderByClause orderBy = new OrderByClause(new TableQueryParser(selectedColumns).sortSpecificationList());
		
		return new Pair<>(selectList, orderBy);
	}

	String getSelectFileColumnName(TableType tableType, Long fileColumnId) {
				
		String selectFileColumnName;
		
		if (fileColumnId == null) {
			// If the column id is not supplied we can infer the file column only for a view or dataset
			if (!TableType.entityview.equals(tableType) && !TableType.dataset.equals(tableType)) {
				IdAndVersion idAndVersion = getSingleTableId().orElseThrow(TableConstants.JOIN_NOT_SUPPORTED_IN_THIS_CONTEXT);
				throw new IllegalArgumentException(String.format("'%s' is not a file view or a dataset, the query.selectFileColumn must be specified", idAndVersion.toString()));
			}
			
			selectFileColumnName = TableConstants.ROW_ID;
		} else {
			selectFileColumnName = getUnionOfAllTableSchemas().stream()
				.filter(selectColumn -> selectColumn.getId().equals(fileColumnId.toString()) && ColumnType.ENTITYID == selectColumn.getColumnType())
				.findFirst()
				.map(ColumnModel::getName)
				.orElseThrow(() -> new IllegalArgumentException("The query.selectFileColumn must be an ENTITYID column that is part of the schema of the underlying table/view"));
		
		}
		
		return SqlElementUtils.wrapInDoubleQuotes(selectFileColumnName);
	}
	
	/**
	 * Lookup a ColumnReference using just the column name or alias
	 * @param columnName
	 * @return
	 */
	public Optional<ColumnTranslationReference> lookupColumnReferenceByName(String columnName) {
		if(columnName == null) {
			return Optional.empty();
		}
		try {
			return lookupColumnReference(new TableQueryParser(SqlElementUtils.wrapInDoubleQuotes(columnName)).columnReference());
		} catch (ParseException e) {
			throw new IllegalArgumentException(e);
		}
	}
	
	/**
	 * Attempt to resolve the given ColumnReference to one of the columns one of the
	 * referenced tables. Optional.empty() returned if no match was found.
	 * 
	 * @param columnReference
	 * @return
	 */
	@Override
	public Optional<ColumnTranslationReference> lookupColumnReference(ColumnReference columnReference) {
		return lookupColumnReferenceMatch(columnReference).map(r -> r.getColumnTranslationReference());
	}

	/**
	 * Attempt to resolve the given ColumnReference to one of the columns one of the
	 * referenced tables. Optional.empty() returned if no match was found.
	 * 
	 * @param columnReference
	 * @return
	 */
	public Optional<ColumnReferenceMatch> lookupColumnReferenceMatch(ColumnReference columnReference) {
		if (columnReference == null) {
			return Optional.empty();
		}
		if (!columnReference.getNameLHS().isPresent() && tables.size() > 1) {
			throw new IllegalArgumentException(
					"Expected a table name or table alias for column: " + columnReference.toSql());
		}
		for (TableInfo table : tables) {
			Optional<ColumnTranslationReference> matchedRef = table.lookupColumnReference(columnReference);
			if (matchedRef.isPresent()) {
				return Optional.of(new ColumnReferenceMatch(table, matchedRef.get()));
			}
		}
		return Optional.empty();
	}
	
	/**
	 * Get the number of tables in the original query.
	 * @return
	 */
	public int getNumberOfTables() {
		return this.tables.size();
	}
	
	/**
	 * Lookup the TableInfo that matches the passed TableNameCorrelation. If no match is found
	 * an Optional.empty() will be returned.
	 * @param tableNameCorrelation
	 * @return
	 */
	public Optional<TableInfo> lookupTableNameCorrelation(TableNameCorrelation tableNameCorrelation){
		if(tableNameCorrelation == null) {
			return Optional.empty();
		}
		return tables.stream().filter(t -> t.isMatch(tableNameCorrelation)).findFirst();
	}

	public ColumnModel getColumnModel(String columnId) {
		return schemaProvider.getColumnModel(columnId);
	}
}
