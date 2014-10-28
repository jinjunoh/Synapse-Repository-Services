CREATE TABLE IF NOT EXISTS `JDONODE` (
  `ID` bigint(20) NOT NULL,
  `CREATED_BY` bigint(20) NOT NULL,
  `CREATED_ON` bigint(20) NOT NULL,
  `CURRENT_REV_NUM` bigint(20) DEFAULT NULL,
  `DESCRIPTION` mediumblob DEFAULT NULL,
  `ETAG` char(36) NOT NULL,
  `NAME` varchar(256) CHARACTER SET latin1 COLLATE latin1_bin NOT NULL,
  `NODE_TYPE` smallint(6) NOT NULL,
  `PARENT_ID` bigint(20) DEFAULT NULL,
  `BENEFACTOR_ID` bigint(20) DEFAULT NULL,
  `PROJECT_ID` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `NODE_UNIQUE_CHILD_NAME` (`PARENT_ID`,`NAME`),
  KEY `JDONODE_KEY_TYPE` (`NODE_TYPE`),
  KEY `JDONODE_KEY_BEN` (`BENEFACTOR_ID`),
  KEY `JDONODE_KEY_PAR` (`PARENT_ID`),
  INDEX `NODE_NAME_INDEX` (`NAME` ASC),
  CONSTRAINT `NODE_PARENT_FK` FOREIGN KEY (`PARENT_ID`) REFERENCES `JDONODE` (`ID`) ON DELETE CASCADE,
  CONSTRAINT `NODE_BENEFACTOR_FK` FOREIGN KEY (`BENEFACTOR_ID`) REFERENCES `JDONODE` (`ID`) ON DELETE CASCADE,
  CONSTRAINT `NODE_PROJECT_FK` FOREIGN KEY (`PROJECT_ID`) REFERENCES `JDONODE` (`ID`) ON DELETE CASCADE,
  CONSTRAINT `NODE_TYPE_FK` FOREIGN KEY (`NODE_TYPE`) REFERENCES `NODE_TYPE` (`ID`),
  CONSTRAINT `NODE_CREATED_BY_FK` FOREIGN KEY (`CREATED_BY`) REFERENCES `JDOUSERGROUP` (`ID`)
)
