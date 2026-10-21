package com.boardinghousebot.state;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public class UserConversationState {

    public enum Step {
        NONE,
        ADMIN_SEARCH_USERS,
        ADMIN_ROLE_CHANGE_INPUT,
        ADMIN_ROLE_CHANGE_CONFIRM,
        CREATE_ROOM_NUMBER,
        CREATE_ROOM_RENT,
        CREATE_ROOM_MAX,
        CREATE_ROOM_AREA,
        CREATE_ROOM_DEPOSIT,
        CREATE_ROOM_DESC,
        CREATE_ROOM_CONFIRM,
        EDIT_ROOM_RENT,
        EDIT_ROOM_AREA,
        EDIT_ROOM_DEPOSIT,
        EDIT_ROOM_DESC,
        EDIT_ROOM_CONFIRM,
        ASSIGN_TENANT_TELEGRAM_ID,
        ASSIGN_TENANT_ROOM_SELECT,
        ASSIGN_TENANT_PHONE,
        EDIT_TENANT_TELEGRAM_ID,
        EDIT_TENANT_PHONE,
        EDIT_TENANT_CONFIRM,
        REMOVE_ROOM_TENANT_SELECT,
        REMOVE_ROOM_TENANT_CONFIRM,
        REMOVE_TENANT_INPUT,
        REMOVE_TENANT_CONFIRM,
        MOVE_TENANT_TARGET_ROOM,
        MOVE_TENANT_MEMBER_TELEGRAM_ID,
        MOVE_TENANT_TARGET_ROOM_SELECT,
        MOVE_TENANT_CONFIRM,
        CREATE_CONTRACT_ROOM_SELECT,
        CREATE_CONTRACT_MEMBER_SELECT,
        CREATE_CONTRACT_START_DATE,
        CREATE_CONTRACT_END_DATE,
        CREATE_CONTRACT_RENT,
        CREATE_CONTRACT_DEPOSIT,
        CREATE_CONTRACT_CONFIRM,
        UPDATE_CONTRACT_START_DATE,
        UPDATE_CONTRACT_END_DATE,
        UPDATE_CONTRACT_RENT,
        UPDATE_CONTRACT_DEPOSIT,
        UPDATE_CONTRACT_CONFIRM,
        UPDATE_UTILITY_PRICE,
        UPDATE_UTILITY_CONFIRM,
        CREATE_METER_CYCLE_ELECTRIC,
        CREATE_METER_CYCLE_WATER,
        CREATE_METER_CYCLE_DATE,
        CREATE_METER_CYCLE_RENT,
        CREATE_METER_CYCLE_CONFIRM,
        CREATE_INVOICE_CYCLE_CONFIRM,
        CREATE_METER_ROOM_SELECT,
        CREATE_METER_TYPE_SELECT,
        CREATE_METER_MONTH,
        CREATE_METER_YEAR,
        CREATE_METER_PREVIOUS,
        CREATE_METER_CURRENT,
        CREATE_METER_UNIT_PRICE,
        CREATE_METER_CONFIRM,
        UPDATE_METER_PREVIOUS,
        UPDATE_METER_CURRENT,
        UPDATE_METER_UNIT_PRICE,
        UPDATE_METER_CONFIRM,
        CREATE_INVOICE_ROOM_SELECT,
        CREATE_INVOICE_MONTH,
        CREATE_INVOICE_YEAR,
        CREATE_INVOICE_CONFIRM,
        RECORD_PAYMENT_INVOICE_SELECT,
        RECORD_PAYMENT_AMOUNT,
        RECORD_PAYMENT_METHOD,
        RECORD_PAYMENT_NOTE,
        RECORD_PAYMENT_CONFIRM,
        CREATE_MAINTENANCE_TITLE,
        CREATE_MAINTENANCE_DESC,
        UPDATE_MAINTENANCE_ID,
        UPDATE_MAINTENANCE_STATUS,
        UPDATE_MAINTENANCE_NOTE
    }

    private Step step = Step.NONE;
    private Long targetRoomId;
    private Long targetUserTelegramId;
    private String roomNumber;
    private BigDecimal monthlyRent;
    private Integer maxOccupants;
    private String description;
    private Map<String, Object> data = new HashMap<>();

    public UserConversationState() {
    }

    public UserConversationState(Step step) {
        this.step = step;
    }

    public Step getStep() {
        return step;
    }

    public void setStep(Step step) {
        this.step = step;
    }

    public Long getTargetRoomId() {
        return targetRoomId;
    }

    public void setTargetRoomId(Long targetRoomId) {
        this.targetRoomId = targetRoomId;
    }

    public Long getTargetUserTelegramId() {
        return targetUserTelegramId;
    }

    public void setTargetUserTelegramId(Long targetUserTelegramId) {
        this.targetUserTelegramId = targetUserTelegramId;
    }

    public String getRoomNumber() {
        return roomNumber;
    }

    public void setRoomNumber(String roomNumber) {
        this.roomNumber = roomNumber;
    }

    public BigDecimal getMonthlyRent() {
        return monthlyRent;
    }

    public void setMonthlyRent(BigDecimal monthlyRent) {
        this.monthlyRent = monthlyRent;
    }

    public Integer getMaxOccupants() {
        return maxOccupants;
    }

    public void setMaxOccupants(Integer maxOccupants) {
        this.maxOccupants = maxOccupants;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}
