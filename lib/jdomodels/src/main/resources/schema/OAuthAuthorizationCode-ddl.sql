CREATE TABLE IF NOT EXISTS `OAUTH_AUTHORIZATION_CODE` (
  `AUTH_CODE` varchar(36) NOT NULL,
  `AUTHORIZATION_REQUEST` mediumblob,
  PRIMARY KEY (`AUTH_CODE`)
)
