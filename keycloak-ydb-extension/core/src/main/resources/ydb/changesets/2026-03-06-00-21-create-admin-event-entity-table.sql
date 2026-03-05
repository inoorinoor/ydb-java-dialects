CREATE TABLE IF NOT EXISTS ADMIN_EVENT_ENTITY
(
    `ID`               Utf8 NOT NULL,
    `ADMIN_EVENT_TIME` Int64,
    `REALM_ID`         Utf8,
    `OPERATION_TYPE`   Utf8,
    `AUTH_REALM_ID`    Utf8,
    `AUTH_CLIENT_ID`   Utf8,
    `AUTH_USER_ID`     Utf8,
    `IP_ADDRESS`       Utf8,
    `RESOURCE_PATH`    Utf8,
    `REPRESENTATION`   Utf8,
    `ERROR`            Utf8,
    `RESOURCE_TYPE`    Utf8,
    `DETAILS_JSON`     Utf8,

    INDEX idx_admin_event_time GLOBAL ON (REALM_ID, ADMIN_EVENT_TIME),
    INDEX idx_admin_event_realm GLOBAL ON (REALM_ID),
    INDEX idx_admin_event_user GLOBAL ON (AUTH_USER_ID),
    INDEX idx_admin_event_client GLOBAL ON (AUTH_CLIENT_ID),
    INDEX idx_admin_event_operation GLOBAL ON (OPERATION_TYPE),
    PRIMARY KEY (ID)
);