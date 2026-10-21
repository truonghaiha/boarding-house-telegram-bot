package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.invoice.*;
import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.meter.*;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.*;
import com.boardinghousebot.state.ConversationStateService;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DetailListInteractionTest {
    final BotMessageSender sender = mock(BotMessageSender.class);
    final User owner = new User(99L, "owner", "Owner", Role.LANDLORD);
    final Room room = new Room("101", new BigDecimal("4000000"), 2, "");
    @Test void invoiceListHasWorkingDetailButtonWithoutCommandText() {
        InvoiceService service = mock(InvoiceService.class);
        PaymentService payments = mock(PaymentService.class);
        var handler = new InvoiceCommandHandler(service, payments, new ConversationStateService());
        Invoice record = new Invoice(room, owner, null, 10, 2026, new BigDecimal("4000000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("4000000"), LocalDate.of(2026, 10, 15)); record.setId(31L);
        when(service.getAllInvoices()).thenReturn(List.of(record));
        when(service.getInvoiceForUser(owner, 31L)).thenReturn(record);
        handler.handle(update("INVOICE_LIST_UNPAID"), owner, sender);
        verify(sender).sendMessageWithKeyboard(eq(99L), argThat(s -> !s.contains("/invoice_detail_")), argThat(k -> k.getKeyboard().stream().flatMap(List::stream)
                .anyMatch(b -> "INVOICE_DETAIL_31".equals(b.getCallbackData()))));
        handler.handle(update("INVOICE_DETAIL_31"), owner, sender); verify(service).getInvoiceForUser(owner, 31L);
    }
    @Test void meterListHasWorkingDetailButtonWithoutCommandText() {
        MeterReadingService service = mock(MeterReadingService.class);
        var handler = new MeterCommandHandler(service, mock(RoomService.class), mock(UserService.class), new ConversationStateService());
        MeterReading record = new MeterReading(room, UtilityType.ELECTRICITY, 10, 2026, BigDecimal.ZERO, BigDecimal.TEN, new BigDecimal("3500")); record.setId(42L);
        when(service.getLatestBillingPeriod()).thenReturn(new int[]{10, 2026});
        when(service.getFilteredReadings(null, null, 10, 2026)).thenReturn(List.of(record));
        when(service.getReadingForUser(owner, 42L)).thenReturn(record);
        handler.handle(update("METER_LIST_ALL"), owner, sender);
        verify(sender).sendMessageWithKeyboard(eq(99L), argThat(s -> !s.contains("/meter_detail_")), argThat(k -> k.getKeyboard().stream().flatMap(List::stream)
                .anyMatch(b -> "METER_DETAIL_42".equals(b.getCallbackData()))));
        handler.handle(update("METER_DETAIL_42"), owner, sender); verify(service).getReadingForUser(owner, 42L);
    }
    @Test void maintenanceListHasWorkingDetailButtonWithoutCommandText() {
        MaintenanceService service = mock(MaintenanceService.class);
        var handler = new MaintenanceCommandHandler(service, new ConversationStateService());
        MaintenanceRequest record = new MaintenanceRequest(owner, room, "Vòi nước", "Hỏng"); record.setId(53L);
        MaintenanceRequest working = new MaintenanceRequest(owner, room, "Mất điện", "Đang sửa"); working.setId(54L);
        working.setStatus(com.boardinghousebot.domain.maintenance.MaintenanceStatus.IN_PROGRESS);
        MaintenanceRequest resolved = new MaintenanceRequest(owner, room, "Đã sửa", "Xong"); resolved.setId(55L);
        resolved.setStatus(com.boardinghousebot.domain.maintenance.MaintenanceStatus.RESOLVED);
        when(service.getAllMaintenanceRequests()).thenReturn(List.of(working, resolved, record));
        when(service.getMaintenanceForUser(owner, 53L)).thenReturn(record);
        handler.handle(update("LANDLORD_MAINTENANCE"), owner, sender);
        verify(sender).sendMessageWithKeyboard(eq(99L), contains("Chọn nhóm sự cố"), argThat(k ->
                k.getKeyboard().stream().flatMap(List::stream).map(b -> b.getCallbackData()).toList()
                        .equals(List.of("MAINTENANCE_LIST_ALL", "MAINTENANCE_LIST_RESOLVED", "MAIN_MENU"))));
        verify(service, never()).getAllMaintenanceRequests();
        clearInvocations(sender);
        handler.handle(update("MAINTENANCE_LIST_ALL"), owner, sender);
        verify(sender).sendMessageWithKeyboard(eq(99L), contains("SỰ CỐ CẦN XỬ LÝ · 2"), argThat(k ->
                k.getKeyboard().stream().flatMap(List::stream).map(b -> b.getCallbackData()).toList()
                        .equals(List.of("MAINTENANCE_DETAIL_53", "MAINTENANCE_DETAIL_54", "LANDLORD_MAINTENANCE", "MAIN_MENU"))
                && k.getKeyboard().get(0).get(0).getText().contains("🆕 Phòng 101 · Vòi nước")));
        handler.handle(update("MAINTENANCE_DETAIL_53"), owner, sender); verify(service).getMaintenanceForUser(owner, 53L);
        verify(sender).sendMessageWithKeyboard(eq(99L), argThat(text -> text.contains("CHI TIẾT SỰ CỐ #53")
                && text.contains("📝 Mô tả: Hỏng") && text.contains("👤 Người báo: Owner\n")
                && !text.contains("@owner")
                && !text.contains("Tiêu đề:") && !text.contains("SĐT:") && !text.contains("Ghi chú xử lý:")), argThat(k ->
                k.getKeyboard().stream().flatMap(List::stream).map(b -> b.getCallbackData()).toList()
                        .equals(List.of("MAINTENANCE_UPDATE_RESOLVED_53", "MAINTENANCE_LIST_ALL"))));
        clearInvocations(sender);
        when(service.getMaintenanceForUser(owner, 54L)).thenReturn(working);
        handler.handle(update("MAINTENANCE_DETAIL_54"), owner, sender);
        verify(sender).sendMessageWithKeyboard(eq(99L), argThat(text -> text.contains("Cần xử lý")
                && !text.contains("Đang xử lý")), argThat(k -> k.getKeyboard().stream().flatMap(List::stream)
                .map(b -> b.getCallbackData()).toList().equals(List.of("MAINTENANCE_UPDATE_RESOLVED_54", "MAINTENANCE_LIST_ALL"))));
        clearInvocations(sender);
        when(service.getMaintenanceForUser(owner, 55L)).thenReturn(resolved);
        handler.handle(update("MAINTENANCE_DETAIL_55"), owner, sender);
        verify(sender).sendMessageWithKeyboard(eq(99L), contains("Đã xử lý"), argThat(k ->
                k.getKeyboard().stream().flatMap(List::stream).map(b -> b.getCallbackData()).toList()
                        .equals(List.of("MAINTENANCE_LIST_RESOLVED"))));
        clearInvocations(sender);
        handler.handle(update("MAINTENANCE_LIST_RESOLVED"), owner, sender);
        verify(sender).sendMessageWithKeyboard(eq(99L), contains("SỰ CỐ ĐÃ XỬ LÝ · 1"), argThat(k ->
                k.getKeyboard().stream().flatMap(List::stream).map(b -> b.getCallbackData()).toList()
                        .equals(List.of("MAINTENANCE_DETAIL_55", "LANDLORD_MAINTENANCE", "MAIN_MENU"))));
        clearInvocations(sender);
        when(service.getAllMaintenanceRequests()).thenReturn(List.of(resolved));
        handler.handle(update("MAINTENANCE_LIST_ALL"), owner, sender);
        verify(sender).sendMessageWithKeyboard(eq(99L), contains("Không có sự cố cần xử lý"), argThat(k ->
                k.getKeyboard().stream().flatMap(List::stream).map(b -> b.getCallbackData()).toList()
                        .equals(List.of("LANDLORD_MAINTENANCE", "MAIN_MENU"))));
    }
    Update update(String action) {
        Message message = new Message(); message.setChat(new Chat(99L, "private"));
        CallbackQuery query = new CallbackQuery(); query.setMessage(message); query.setData(action);
        Update update = new Update(); update.setCallbackQuery(query); return update;
    }
}
