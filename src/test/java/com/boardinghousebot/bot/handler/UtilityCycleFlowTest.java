package com.boardinghousebot.bot.handler;
import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.meter.UtilityCycle;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.*;
import com.boardinghousebot.service.UtilityCycleService;
import com.boardinghousebot.state.*;
import com.boardinghousebot.util.BillingDates;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class UtilityCycleFlowTest {
    UtilityCycleService service=mock(UtilityCycleService.class);
    ConversationStateService states=new ConversationStateService();
    BotMessageSender sender=mock(BotMessageSender.class);
    UtilityCycleFlow flow=new UtilityCycleFlow(service,states);
    User owner=new User(99L,"owner","Owner",Role.LANDLORD);
    UtilityCycle c=new UtilityCycle();
    @BeforeEach void setup() {
        var room=new Room("101",new BigDecimal("4000000"),3,"");room.setId(1L);
        var member=new User(1L,"member","Member",Role.MEMBER);
        var contract=new Contract(member,room,BillingDates.today().minusMonths(1),BillingDates.today().plusMonths(6),new BigDecimal("4000000"),BigDecimal.ZERO);contract.setId(1L);
        c.setId(1L);c.setVersion(1L);c.setContract(contract);c.setPeriodStart(contract.getStartDate());c.setPeriodEnd(BillingDates.today());c.setPlannedEnd(BillingDates.today());c.setReadingDate(BillingDates.today());
        c.setPreviousElectricity(new BigDecimal("1000"));c.setPreviousWater(new BigDecimal("100"));c.setRent(contract.getMonthlyRent());
        when(service.get(99L,1L)).thenReturn(c);when(service.open(99L,1L)).thenReturn(c);when(service.invoiceId(1L)).thenReturn(Optional.empty());
        when(service.reading(eq(99L),eq(1L),anyBoolean(),any())).thenAnswer(call -> {
            if(call.getArgument(2,Boolean.class)) c.setElectricity(call.getArgument(3)); else c.setWater(call.getArgument(3));
            c.setVersion(c.getVersion()+1);return c;
        });
        when(service.estimate(99L,1L)).thenReturn(new UtilityCycleService.Estimate(new BigDecimal("3000"),new BigDecimal("35000"),new BigDecimal("300000"),new BigDecimal("350000")));
    }
    void meter(String action) { assertTrue(flow.meter(99L,owner,action,states.getState(99L),sender)); }
    @Test void memberMeterButtonShowsCurrentPeriodImmediatelyWithOnlyMainMenu() {
        var member=c.getContract().getMember();
        for(boolean finalized:List.of(false,true)) {
            clearInvocations(sender);
            when(service.currentForMember(1L)).thenReturn(Optional.of(new UtilityCycleService.MemberCycleView(
                    "101",c.getPeriodStart(),c.getPeriodEnd(),new BigDecimal("1000"),finalized?new BigDecimal("1100"):null,
                    new BigDecimal("100"),finalized?new BigDecimal("110"):null,finalized)));
            assertTrue(flow.meter(1L,member,"MEMBER_METERS",states.getState(1L),sender));
            verify(sender).sendMessageWithKeyboard(eq(1L),argThat(text ->
                    text.contains("ĐIỆN NƯỚC KỲ HIỆN TẠI") && text.contains("Phòng: 101")
                    && text.contains("Chỉ số đầu kỳ: 1000 kWh") && text.contains("Chỉ số đầu kỳ: 100 m³")
                    && (finalized?text.contains("Chỉ số cuối kỳ: 1100 kWh") && text.contains("Chỉ số cuối kỳ: 110 m³")
                        && text.contains("Tình trạng: Đã chốt kỳ")
                        :text.contains("Chỉ số cuối kỳ: Chưa chốt") && text.contains("chưa phát sinh tiền điện nước"))),argThat(keyboard ->
                    keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getCallbackData()).toList()
                        .equals(List.of("MAIN_MENU"))));
            assertEquals(UserConversationState.Step.NONE,states.getState(1L).getStep());
        }
        verify(service,never()).list(anyLong());
        verify(service,never()).open(anyLong(),anyLong());
    }
    @Test void memberMeterButtonHasEmptyStateWithoutFallingBackToOldPeriods() {
        when(service.currentForMember(1L)).thenReturn(Optional.empty());
        assertTrue(flow.meter(1L,c.getContract().getMember(),"MEMBER_METERS",states.getState(1L),sender));
        verify(sender).sendMessageWithKeyboard(eq(1L),contains("Chưa có kỳ điện nước được phép xem"),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getCallbackData()).toList()
                    .equals(List.of("MAIN_MENU"))));
        verify(service,never()).list(anyLong());
    }
    @Test void memberCanOpenConfirmedInitialReadingsFromListWithoutEditingOrBilling() {
        var member=c.getContract().getMember();
        c.setBaseline(true); c.setFinalized(true); c.setPeriodEnd(c.getPeriodStart());
        c.setElectricity(new BigDecimal("1000")); c.setWater(new BigDecimal("100"));
        when(service.list(1L)).thenReturn(List.of(c));
        when(service.get(1L,1L)).thenReturn(c);
        assertTrue(flow.meter(1L,member,"MEMBER_METER_ALL",states.getState(1L),sender));
        verify(sender).sendMessageWithKeyboard(eq(1L),anyString(),argThat(keyboard ->
                keyboard.getKeyboard().get(0).get(0).getText().startsWith("Phòng 101 · Chỉ số ban đầu · ")
                && keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getCallbackData()).toList()
                    .equals(List.of("MEMBER_CYCLE_DETAIL_1","MAIN_MENU"))));
        clearInvocations(sender);
        assertTrue(flow.meter(1L,member,"MEMBER_CYCLE_DETAIL_1",states.getState(1L),sender));
        verify(sender).sendMessageWithKeyboard(eq(1L),argThat(text ->
                text.contains("CHỈ SỐ BAN ĐẦU PHÒNG 101")
                && text.contains("Chỉ số điện ban đầu: 1000 kWh")
                && text.contains("Chỉ số nước ban đầu: 100 m³")
                && text.contains("Đã xác nhận chỉ số ban đầu")
                && text.contains("Mốc ban đầu chưa có chỉ số cuối kỳ, chưa phát sinh tiền điện nước.")
                && !text.contains("Chưa ghi số cuối kỳ")),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getCallbackData()).toList()
                    .equals(List.of("MEMBER_METERS"))));
        assertEquals(UserConversationState.Step.NONE,states.getState(1L).getStep());
        verify(service,never()).open(anyLong(),anyLong());
        verify(service,never()).reading(anyLong(),anyLong(),anyBoolean(),any());
        verify(service,never()).publish(anyLong(),anyLong(),anyLong());
    }
    @Test void memberListKeepsAuthorizedInitialReadingsAndPeriodsAfterContractEnds() {
        var member=c.getContract().getMember();
        c.setFinalized(true);
        c.getContract().setStatus(com.boardinghousebot.domain.contract.ContractStatus.ENDED);
        var baseline=new UtilityCycle();
        baseline.setId(2L); baseline.setContract(c.getContract()); baseline.setBaseline(true); baseline.setFinalized(true);
        baseline.setPeriodStart(c.getPeriodStart()); baseline.setPeriodEnd(c.getPeriodStart());
        when(service.list(1L)).thenReturn(List.of(c,baseline));
        assertTrue(flow.meter(1L,member,"MEMBER_METER_ALL",states.getState(1L),sender));
        verify(sender).sendMessageWithKeyboard(eq(1L),contains("Chọn một kỳ"),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getCallbackData()).toList()
                    .equals(List.of("MEMBER_CYCLE_DETAIL_1","MEMBER_CYCLE_DETAIL_2","MAIN_MENU"))));
    }
    @Test void upcomingRoomsShowDueDatesAndOpenExistingOrUnopenedPeriods() {
        var today=BillingDates.today();
        when(service.upcomingInvoiceRooms(99L)).thenReturn(List.of(
                new UtilityCycleService.DueRoom(1L,"101",today.minusDays(2),10L),
                new UtilityCycleService.DueRoom(2L,"102",today,null),
                new UtilityCycleService.DueRoom(3L,"103",today.plusDays(3),30L)));
        assertTrue(flow.invoice(99L,owner,"INVOICE_LIST_DUE",states.getState(99L),sender));
        verify(sender).sendMessageWithKeyboard(eq(99L),contains("3 ngày"),argThat(keyboard -> {
            var buttons=keyboard.getKeyboard().stream().flatMap(Collection::stream).toList();
            return buttons.get(0).getText().contains("Trễ 2 ngày")
                    && buttons.get(1).getText().contains("Hôm nay")
                    && buttons.get(2).getText().contains("Còn 3 ngày")
                    && buttons.stream().map(b -> b.getCallbackData()).toList()
                        .equals(List.of("METER_CYCLE_DETAIL_10","METER_CYCLE_OPEN_2","METER_CYCLE_DETAIL_30","LANDLORD_INVOICES"));
        }));
        verify(service,never()).open(anyLong(),anyLong());
    }
    @Test void upcomingRoomsHasEmptyState() {
        when(service.upcomingInvoiceRooms(99L)).thenReturn(List.of());
        assertTrue(flow.invoice(99L,owner,"INVOICE_LIST_DUE",states.getState(99L),sender));
        verify(sender).sendMessageWithKeyboard(eq(99L),contains("Không có phòng"),any());
    }
    @Test void createInvoiceOpensSharedRoomSelectionWithoutSeparateInvoiceWizard() {
        when(service.activeContracts(99L)).thenReturn(List.of(c.getContract()));
        assertTrue(flow.invoice(99L,owner,"INVOICE_ADD",states.getState(99L),sender));
        verify(sender).sendMessageWithKeyboard(eq(99L),argThat(text -> text.contains("💰 TẠO HÓA ĐƠN")
                && text.contains("Hóa đơn được tạo tự động sau khi xác nhận chốt")),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getCallbackData()).toList()
                        .equals(List.of("METER_CYCLE_OPEN_1","LANDLORD_INVOICES"))));
        assertEquals(UserConversationState.Step.NONE,states.getState(99L).getStep());
        verify(service,never()).open(anyLong(),anyLong());
        meter("METER_CYCLE_OPEN_1");
        verify(service).open(99L,1L);
        verify(service,never()).publish(anyLong(),anyLong(),anyLong());
    }
    @Test void createInvoiceShowsExistingBillInsteadOfStartingSamePeriodAgain() {
        c.setFinalized(true);
        when(service.activeContracts(99L)).thenReturn(List.of(c.getContract()));
        when(service.list(99L)).thenReturn(List.of(c));
        when(service.invoiceId(1L)).thenReturn(Optional.of(42L));
        assertTrue(flow.invoice(99L,owner,"INVOICE_ADD",states.getState(99L),sender));
        verify(sender).sendMessageWithKeyboard(eq(99L),anyString(),argThat(keyboard ->
                keyboard.getKeyboard().get(0).get(0).getCallbackData().equals("INVOICE_DETAIL_42")));
        verify(service,never()).open(anyLong(),anyLong());
        verify(service,never()).publish(anyLong(),anyLong(),anyLong());
    }
    @Test void listNavigatesFromRoomsToAllTheirPeriodsWithoutFilterButtons() {
        var baseline=new UtilityCycle();
        baseline.setId(2L); baseline.setContract(c.getContract()); baseline.setBaseline(true);
        baseline.setFinalized(true); baseline.setReadingDate(c.getContract().getStartDate());
        var otherRoom=new Room("102",BigDecimal.TEN,2,""); otherRoom.setId(2L);
        var otherContract=new Contract(c.getContract().getMember(),otherRoom,c.getPeriodStart(),c.getPeriodEnd(),BigDecimal.TEN,BigDecimal.ZERO);
        var other=new UtilityCycle(); other.setId(3L); other.setContract(otherContract);
        var previous=new UtilityCycle(); previous.setId(4L); previous.setContract(c.getContract());
        previous.setPeriodStart(c.getPeriodStart().minusMonths(1)); previous.setPeriodEnd(c.getPeriodStart());
        var endedContract=new Contract(c.getContract().getMember(),c.getContract().getRoom(),c.getPeriodStart(),c.getPeriodEnd(),BigDecimal.TEN,BigDecimal.ZERO);
        endedContract.setStatus(com.boardinghousebot.domain.contract.ContractStatus.ENDED);
        var staleDraft=new UtilityCycle(); staleDraft.setId(5L); staleDraft.setContract(endedContract);
        staleDraft.setPeriodStart(c.getPeriodStart()); staleDraft.setPeriodEnd(c.getPeriodEnd());
        var oldFinalized=new UtilityCycle(); oldFinalized.setId(6L); oldFinalized.setContract(endedContract);
        oldFinalized.setPeriodStart(c.getPeriodStart()); oldFinalized.setPeriodEnd(c.getPeriodEnd()); oldFinalized.setFinalized(true);
        previous.setFinalized(true);
        when(service.list(99L)).thenReturn(List.of(c,baseline,staleDraft,oldFinalized,previous,other));
        meter("METER_LIST_ALL");
        verify(sender).sendMessageWithKeyboard(eq(99L),contains("Chọn phòng"),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getCallbackData()).toList()
                        .equals(List.of("METER_CYCLE_LIST_ROOM_1","METER_CYCLE_LIST_ROOM_2","LANDLORD_METERS","MAIN_MENU"))));
        clearInvocations(sender);
        meter("METER_CYCLE_LIST_ROOM_1");
        verify(sender).sendMessageWithKeyboard(eq(99L),contains("Chọn một kỳ"),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getCallbackData()).toList()
                        .equals(List.of("METER_CYCLE_VIEW_1","METER_CYCLE_VIEW_4","METER_LIST_ALL","MAIN_MENU"))
                && keyboard.getKeyboard().get(0).get(0).getText().equals("Kỳ này · "+c.periodLabel())
                && keyboard.getKeyboard().get(1).get(0).getText().equals("Kỳ trước · "+previous.periodLabel())));
        clearInvocations(sender);
        c.setElectricity(new BigDecimal("1100")); c.setWater(new BigDecimal("110"));
        meter("METER_CYCLE_VIEW_1");
        verify(sender).sendMessageWithKeyboard(eq(99L),argThat(text ->
                text.contains("PHÒNG 101") && text.contains("Kỳ: "+c.periodLabel())
                && text.contains("Số bắt đầu: 1000 kWh") && text.contains("Số kết thúc: 1100 kWh")
                && text.contains("Số bắt đầu: 100 m³") && text.contains("Số kết thúc: 110 m³")),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getCallbackData()).toList()
                        .equals(List.of("METER_CYCLE_LIST_ROOM_1","MAIN_MENU"))));
        assertEquals(UserConversationState.Step.NONE,states.getState(99L).getStep());
        clearInvocations(sender);
        when(service.get(99L,6L)).thenReturn(oldFinalized);
        meter("METER_CYCLE_VIEW_6");
        verify(sender).sendNotice(eq(99L),contains("thuộc hợp đồng cũ"));
        verify(sender,never()).sendMessageWithKeyboard(anyLong(),anyString(),any());
    }
    @Test void enteringElectricityThenWaterPersistsDraftAndShowsConfirmation() {
        meter("METER_CYCLE_INPUT_1");meter("1100");
        assertEquals(UserConversationState.Step.CREATE_METER_CYCLE_WATER,states.getState(99L).getStep());
        verify(service).reading(99L,1L,true,new BigDecimal("1100"));
        meter("110");
        assertEquals(UserConversationState.Step.CREATE_METER_CYCLE_CONFIRM,states.getState(99L).getStep());
        verify(sender).sendMessageWithKeyboard(eq(99L),contains("Tổng điện nước dự kiến:"),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getText()).toList()
                        .equals(List.of("✅ Xác nhận lưu","❌ Hủy"))
                && keyboard.getKeyboard().get(0).get(0).getCallbackData().equals("METER_CYCLE_SAVE_1")));
        meter("METER_CYCLE_SAVE_1");
        assertEquals(UserConversationState.Step.NONE,states.getState(99L).getStep());
        verify(service,never()).finalizeCycle(anyLong(),anyLong(),anyLong());
        verify(service,never()).finalizePeriod(anyLong(),anyLong(),anyLong());
    }
    @Test void cancellationResetsClosingAndClearsConversation() {
        meter("METER_CYCLE_INPUT_1");meter("1100");meter("/cancel");
        assertEquals(UserConversationState.Step.NONE,states.getState(99L).getStep());
        verify(service).reading(99L,1L,true,new BigDecimal("1100"));
        verify(service).cancelDraft(99L,1L);
        verify(sender).sendMessageWithKeyboard(eq(99L),eq("Đã hủy thao tác. Các chỉ số đã nhập được giữ nguyên."),any());
        verify(service,never()).finalizeCycle(anyLong(),anyLong(),anyLong());
    }
    @Test void staleConfirmCannotFinalizeDifferentCycle() {
        meter("METER_CYCLE_FINALIZE_1");
        verify(service,never()).finalizeCycle(anyLong(),anyLong(),anyLong());
        verify(sender).sendNotice(eq(99L),contains("hết hiệu lực"));
    }
    @Test void periodMenuClosesOnScheduleUsingCurrentConfirmation() {
        meter("METER_CYCLE_OPEN_1");
        verify(sender).sendMessageWithKeyboard(eq(99L),argThat(text ->
                !text.contains("Ngày đọc thực tế") && !text.contains("Chưa đến ngày chốt")),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getText()).toList()
                    .equals(List.of("✏️ Ghi số cuối kỳ","🚪 Chốt trả phòng sớm","📅 Chốt theo kỳ","📋 Danh sách chỉ số","❌ Hủy"))));
        when(service.confirmAndInvoice(99L,1L,1L,true)).thenAnswer(call -> {
            c.setElectricityPrice(new BigDecimal("3000")); c.setWaterPrice(new BigDecimal("35000"));
            c.setFinalized(true); return c;
        });
        c.setElectricity(new BigDecimal("1100")); c.setWater(new BigDecimal("110"));
        clearInvocations(sender);
        meter("METER_CYCLE_PERIOD_1");
        verify(service,never()).confirmAndInvoice(anyLong(),anyLong(),anyLong(),anyBoolean());
        verify(sender).sendMessageWithKeyboard(eq(99L),anyString(),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getText()).toList()
                        .equals(List.of("✅ Xác nhận chốt kỳ","❌ Hủy"))));
        meter("METER_CYCLE_FINALIZE_1");
        verify(service).confirmAndInvoice(99L,1L,1L,true);
        verify(service,never()).adjust(anyLong(),anyLong(),any(),anyBoolean());
    }
    @Test void checkoutUsesFullContractRentWithoutExtraAmountPrompt() {
        c.setElectricity(new BigDecimal("1100")); c.setWater(new BigDecimal("110"));
        when(service.adjust(99L,1L,null,true)).thenAnswer(call -> { c.setCheckout(true); c.setAdjusted(true); return c; });
        meter("METER_CYCLE_CHECKOUT_1");
        verify(service).adjust(99L,1L,null,true);
        verify(sender).sendMessageWithKeyboard(eq(99L),anyString(),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getText()).toList()
                        .equals(List.of("✅ Xác nhận chốt kỳ","❌ Hủy"))));
        verify(service,never()).finalizeCycle(anyLong(),anyLong(),anyLong());
        verify(sender,never()).sendPrompt(anyLong(),contains("Nhập tiền phòng"));
    }
    @Test void savingHandoverReadingsOpensFirstUsagePeriod() {
        // Keep c as the usage period returned after saving the separate handover record.
        var start=java.time.LocalDate.of(2026,10,20);
        var end=java.time.LocalDate.of(2026,11,20);
        c.getContract().setStartDate(start); c.getContract().setEndDate(end);
        c.setId(2L); c.setPeriodStart(start); c.setPeriodEnd(end); c.setPlannedEnd(end);
        var baseline=new UtilityCycle();
        baseline.setId(1L); baseline.setVersion(1L); baseline.setContract(c.getContract());
        baseline.setBaseline(true); baseline.setPeriodStart(start); baseline.setPeriodEnd(start); baseline.setReadingDate(start);
        when(service.open(99L,1L)).thenReturn(baseline);
        when(service.get(99L,1L)).thenReturn(baseline);
        when(service.finalizeCycle(99L,1L,1L)).thenAnswer(call -> { baseline.setFinalized(true); return baseline; });
        when(service.openContract(99L,1L)).thenReturn(c);
        when(service.invoiceId(2L)).thenReturn(Optional.empty());

        meter("METER_CYCLE_OPEN_1");
        verify(sender).sendMessageWithKeyboard(eq(99L),argThat(text ->
                text.equals("⚡💧 CHỈ SỐ BAN ĐẦU PHÒNG 101\nNgày bắt đầu: 20/10/2026\nKỳ hạn: 20/10/2026 – 20/11/2026"
                    +"\n\nNhập chỉ số điện, nước lúc bắt đầu thuê phòng.\n\nNhập chỉ số điện ban đầu (kWh):")),any());
        assertEquals(UserConversationState.Step.CREATE_METER_CYCLE_ELECTRIC,states.getState(99L).getStep());
        assertEquals(1L,states.getState(99L).getData().get("cycleId"));
        verify(sender).sendMessageWithKeyboard(eq(99L),anyString(),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream)
                        .map(button -> button.getText()).toList().equals(List.of("❌ Hủy"))
                && keyboard.getKeyboard().get(0).get(0).getCallbackData().equals("/cancel")));
        meter("METER_CYCLE_DATE_1");
        verify(sender).sendNotice(eq(99L),contains("Chức năng sửa ngày đọc đã được bỏ"));
        clearInvocations(sender);
        baseline.setElectricity(new BigDecimal("1000")); baseline.setWater(new BigDecimal("100"));
        meter("METER_CYCLE_DETAIL_1");
        verify(sender).sendMessageWithKeyboard(eq(99L),anyString(),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream).map(button -> button.getText()).toList()
                        .equals(List.of("✅ Xác nhận lưu","❌ Hủy"))
                && keyboard.getKeyboard().get(0).get(0).getCallbackData().equals("METER_CYCLE_FINALIZE_1")));
        clearInvocations(sender);
        meter("METER_CYCLE_FINALIZE_1");

        verify(service).openContract(99L,1L);
        verify(sender).sendMessageWithKeyboard(eq(99L),argThat(text ->
                text.contains("✅ ĐÃ LƯU CHỈ SỐ BAN ĐẦU PHÒNG 101")
                && text.contains("Kỳ hạn: 20/10/2026 – 20/11/2026")
                && text.contains("⚡ Điện đầu kỳ: 1000 kWh")
                && text.contains("💧 Nước đầu kỳ: 100 m³")
                && text.contains("Đến ngày 20/11/2026, hãy ghi chỉ số cuối kỳ")
                && !text.contains("Ngày đọc thực tế")),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream)
                        .map(button -> button.getCallbackData()).toList()
                        .equals(List.of("METER_CYCLE_INPUT_2","METER_LIST_ALL","MAIN_MENU"))));
        assertEquals(UserConversationState.Step.NONE,states.getState(99L).getStep());
        when(service.get(99L,2L)).thenReturn(c);
        meter("METER_CYCLE_INPUT_2");
        assertEquals(2L,states.getState(99L).getData().get("cycleId"));
        assertEquals(UserConversationState.Step.CREATE_METER_CYCLE_ELECTRIC,states.getState(99L).getStep());
    }
    @Test void usagePeriodHasNoDateEditAndRejectsOldDateButtonAndPendingInput() {
        meter("METER_CYCLE_OPEN_1");
        verify(sender).sendMessageWithKeyboard(eq(99L),anyString(),argThat(keyboard ->
                keyboard.getKeyboard().stream().flatMap(Collection::stream)
                        .noneMatch(button -> button.getCallbackData().startsWith("METER_CYCLE_DATE_"))));
        clearInvocations(sender,service);
        meter("METER_CYCLE_DATE_1");
        verify(sender).sendNotice(eq(99L),contains("Chức năng sửa ngày đọc đã được bỏ"));
        verifyNoInteractions(service);
        clearInvocations(sender);
        states.getState(99L).setStep(UserConversationState.Step.CREATE_METER_CYCLE_DATE);
        meter("20/10/2026");
        verify(sender).sendNotice(eq(99L),contains("Chức năng sửa ngày đọc đã được bỏ"));
        assertEquals(UserConversationState.Step.NONE,states.getState(99L).getStep());
        verifyNoInteractions(service);
    }
}
