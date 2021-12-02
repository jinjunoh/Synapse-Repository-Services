package org.sagebionetworks.repo.manager.table;

import java.util.HashSet;
import java.util.Set;

import org.sagebionetworks.repo.model.dbo.dao.table.MaterializedViewDao;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.table.MaterializedView;
import org.sagebionetworks.repo.model.table.TableConstants;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.table.query.ParseException;
import org.sagebionetworks.table.query.TableQueryParser;
import org.sagebionetworks.table.query.model.QuerySpecification;
import org.sagebionetworks.table.query.model.TableNameCorrelation;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MaterializedViewManagerImpl implements MaterializedViewManager {

	private MaterializedViewDao dao;
	
	@Autowired
	public MaterializedViewManagerImpl(MaterializedViewDao dao) {
		this.dao = dao;
	}
	
	@Override
	public void validate(MaterializedView materializedView) {
		ValidateArgument.required(materializedView, "The materialzied view");		
		ValidateArgument.requiredNotBlank(materializedView.getDefiningSQL(), "The materialized view definingSQL");

		getQuerySpecification(materializedView).getSingleTableName().orElseThrow(TableConstants.JOIN_NOT_SUPPORTED_IN_THIS_CONTEXT);
		
	}
	
	@Override
	@WriteTransaction
	public void registerSourceTables(MaterializedView materializedView) {
		ValidateArgument.required(materializedView, "The materialized view");		
		ValidateArgument.requiredNotBlank(materializedView.getDefiningSQL(), "The materialized view definingSQL");
		ValidateArgument.requiredNotBlank(materializedView.getId(), "The id of the materialized view");
		
		IdAndVersion idAndVersion = KeyFactory.idAndVersion(materializedView.getId(), materializedView.getVersionNumber());
		
		QuerySpecification querySpecification = getQuerySpecification(materializedView);
		
		Set<IdAndVersion> newSourceTables = getSourceTableIds(querySpecification);
		Set<IdAndVersion> currentSourceTables = dao.getSourceTables(idAndVersion);
		
		if (newSourceTables.equals(currentSourceTables)) {
			return;
		}
		
		Set<IdAndVersion> toDelete = new HashSet<>(currentSourceTables);
		
		toDelete.removeIf(newSourceTables::contains);
		
		dao.deleteSourceTables(idAndVersion, toDelete);
		dao.addSourceTables(idAndVersion, newSourceTables);
		
	}
	
	private static QuerySpecification getQuerySpecification(MaterializedView materializedView) {
		try {
			return TableQueryParser.parserQuery(materializedView.getDefiningSQL());
		} catch (ParseException e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}
	}
		
	private static Set<IdAndVersion> getSourceTableIds(QuerySpecification querySpecification) {
		Set<IdAndVersion> sourceTableIds = new HashSet<>();
		
		for (TableNameCorrelation table : querySpecification.createIterable(TableNameCorrelation.class)) {
			sourceTableIds.add(IdAndVersion.parse(table.getTableName().toSql()));
		}
		
		return sourceTableIds;
	}

}
