/**
 * The first table EI, defines the entity information for a given batch of entity IDs.
 * The second table EAR gathers all of the access restrictions for each entity based on the entity's hierarchy.
 * The third table APS gathers the access approval state for the user for each access restriction on each entity.
 * Finally all of the information is gathered into a single row for each access restriction on each entity.
 */
WITH 
	EI AS (
		SELECT N.ID AS ENTITY_ID, N.PARENT_ID, N.NODE_TYPE, N.CREATED_BY FROM NODE N WHERE N.ID IN(:entityIds)
	),
	EAR AS (
		WITH RECURSIVE EAR (ENTITY_ID, PARENT_ID, REQUIREMENT_ID, DISTANCE) AS (
			SELECT EI.ENTITY_ID, EI.PARENT_ID, NAR.REQUIREMENT_ID, 1 FROM EI 
				LEFT JOIN NODE_ACCESS_REQUIREMENT NAR ON (EI.ENTITY_ID = NAR.SUBJECT_ID AND NAR.SUBJECT_TYPE = 'ENTITY')
			UNION ALL 
			SELECT EAR.ENTITY_ID, N.PARENT_ID, NAR.REQUIREMENT_ID, EAR.DISTANCE+ 1 FROM NODE AS N
				 JOIN EAR ON (N.ID = EAR.PARENT_ID)
				 LEFT JOIN NODE_ACCESS_REQUIREMENT NAR ON (N.ID = NAR.SUBJECT_ID AND NAR.SUBJECT_TYPE = 'ENTITY')
				 	 WHERE N.ID IS NOT NULL AND DISTANCE < :depth
		)
		SELECT distinct ENTITY_ID, REQUIREMENT_ID FROM EAR WHERE REQUIREMENT_ID IS NOT NULL
	), 
	APS AS ( 
		SELECT EAR.*, if(AA.STATE = 'APPROVED', TRUE, FALSE) AS APPROVED FROM EAR
			LEFT JOIN ACCESS_APPROVAL AA
				ON (EAR.REQUIREMENT_ID = AA.REQUIREMENT_ID AND AA.ACCESSOR_ID = :userId AND AA.STATE = 'APPROVED')
	 )
SELECT 
	EI.ENTITY_ID,
	EI.NODE_TYPE,
	EI.CREATED_BY,
	APS.REQUIREMENT_ID,
	APS.APPROVED,
	AR.CONCRETE_TYPE AS REQUIREMENT_TYPE,
	AR.IS_TWO_FA_REQUIRED
		FROM EI LEFT JOIN APS ON (EI.ENTITY_ID = APS.ENTITY_ID)	
	 			LEFT JOIN ACCESS_REQUIREMENT AR ON (APS.REQUIREMENT_ID = AR.ID)
