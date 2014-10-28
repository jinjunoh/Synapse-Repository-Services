CREATE TABLE IF NOT EXISTS `MESSAGE_RECIPIENT` (
  `MESSAGE_ID` bigint(20) NOT NULL,
  `RECIPIENT_ID` bigint(20) NOT NULL, 
  PRIMARY KEY (`MESSAGE_ID`, `RECIPIENT_ID`),
  CONSTRAINT `MESSAGE_RECIPIENT_ID_FK` FOREIGN KEY (`MESSAGE_ID`) REFERENCES `MESSAGE_CONTENT` (`ID`) ON DELETE CASCADE, 
  CONSTRAINT `MESSAGE_RECIPIENT_USER_FK` FOREIGN KEY (`RECIPIENT_ID`) REFERENCES `JDOUSERGROUP` (`ID`) ON DELETE CASCADE
)
