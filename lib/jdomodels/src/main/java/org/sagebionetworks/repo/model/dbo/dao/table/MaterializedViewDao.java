package org.sagebionetworks.repo.model.dbo.dao.table;

import java.util.Set;

import org.sagebionetworks.repo.model.entity.IdAndVersion;

/**
 * DAO supporting extra data used for materialized views
 */
public interface MaterializedViewDao {

	/**
	 * Clear all the source tables associated with the materialized view with the given id
	 * 
	 * @param viewId The id and version of the materialized view
	 */
	void clearSourceTables(IdAndVersion viewId);
	
	/**
	 * Associates the given set of source table ids to the materialized view with the given id
	 * 
	 * @param viewId The id and version of the materialized view
	 * @param sourceTableIds The list of table ids associated with the view
	 */
	void addSourceTables(IdAndVersion viewId, Set<IdAndVersion> sourceTableIds);
	
	/**
	 * Removes the association of the given set of source table ids from the materialized view with the given id 
	 * 
	 * @param viewId
	 * @param sourceTableIds
	 */
	void deleteSourceTables(IdAndVersion viewId, Set<IdAndVersion> sourceTableIds);
	
	/**
	 * @param viewId The id of the materialized view
	 * @return The set of source table ids currently associated with the materialized view with the given id
	 */
	Set<IdAndVersion> getSourceTables(IdAndVersion viewId);
	
}
