package com.boardinghousebot.domain.audit;

public final class AuditAction {

    public static final String USER_REGISTER = "USER_REGISTER";
    public static final String ADMIN_PROMOTE_LANDLORD = "ADMIN_PROMOTE_LANDLORD";
    public static final String ADMIN_REVOKE_LANDLORD = "ADMIN_REVOKE_LANDLORD";

    public static final String LANDLORD_CREATE_ROOM = "LANDLORD_CREATE_ROOM";
    public static final String LANDLORD_UPDATE_ROOM = "LANDLORD_UPDATE_ROOM";
    public static final String LANDLORD_DELETE_ROOM = "LANDLORD_DELETE_ROOM";
    public static final String LANDLORD_ASSIGN_MEMBER = "LANDLORD_ASSIGN_MEMBER";
    public static final String LANDLORD_REMOVE_MEMBER = "LANDLORD_REMOVE_MEMBER";
    public static final String LANDLORD_MOVE_MEMBER = "LANDLORD_MOVE_MEMBER";

    public static final String LANDLORD_CREATE_CONTRACT = "LANDLORD_CREATE_CONTRACT";
    public static final String LANDLORD_UPDATE_CONTRACT = "LANDLORD_UPDATE_CONTRACT";
    public static final String LANDLORD_END_CONTRACT = "LANDLORD_END_CONTRACT";

    public static final String LANDLORD_CREATE_METER_READING = "LANDLORD_CREATE_METER_READING";
    public static final String LANDLORD_UPDATE_METER_READING = "LANDLORD_UPDATE_METER_READING";
    public static final String LANDLORD_DELETE_METER_READING = "LANDLORD_DELETE_METER_READING";

    public static final String LANDLORD_CREATE_INVOICE = "LANDLORD_CREATE_INVOICE";
    public static final String LANDLORD_RECORD_PAYMENT = "LANDLORD_RECORD_PAYMENT";

    public static final String MEMBER_CREATE_MAINTENANCE = "MEMBER_CREATE_MAINTENANCE";
    public static final String LANDLORD_UPDATE_MAINTENANCE = "LANDLORD_UPDATE_MAINTENANCE";
    public static final String LANDLORD_UPDATE_MAINTENANCE_STATUS = "LANDLORD_UPDATE_MAINTENANCE_STATUS";
    public static final String LANDLORD_RESOLVE_MAINTENANCE = "LANDLORD_RESOLVE_MAINTENANCE";
    public static final String NOTIFICATION_CREATED = "NOTIFICATION_CREATED";

    private AuditAction() {
    }
}
