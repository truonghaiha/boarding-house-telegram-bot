package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.invoice.*;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.*;
import com.boardinghousebot.service.*;
import com.boardinghousebot.state.ConversationStateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InvoiceGroupsTest {
    InvoiceService service=mock(InvoiceService.class);
    BotMessageSender sender=mock(BotMessageSender.class);
    User owner=new User(99L,"owner","Owner",Role.LANDLORD);
    InvoiceCommandHandler handler=new InvoiceCommandHandler(service,mock(PaymentService.class),new ConversationStateService());

    void open(String action) {
        var chat=new Chat(99L,"private");
        var message=new Message(); message.setChat(chat);
        var callback=new CallbackQuery(); callback.setMessage(message); callback.setData(action);
        var update=new Update(); update.setCallbackQuery(callback);
        handler.handle(update,owner,sender);
    }
    InlineKeyboardMarkup keyboard() {
        var captured=ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender).sendMessageWithKeyboard(eq(99L),anyString(),captured.capture());
        return captured.getValue();
    }
    List<String> actions() {
        return keyboard().getKeyboard().stream().flatMap(List::stream).map(b -> b.getCallbackData()).toList();
    }
    Invoice bill(long id, InvoiceStatus status, String paid) {
        var room=new Room("102",BigDecimal.TEN,2,""); room.setId(1L);
        var invoice=new Invoice(room,null,null,10,2026,new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,
                new BigDecimal("100"),LocalDate.of(2026,11,20));
        invoice.setId(id); invoice.setStatus(status); invoice.setPaidAmount(new BigDecimal(paid));
        invoice.setPeriodStart(LocalDate.of(2026,10,20)); invoice.setPeriodEnd(LocalDate.of(2026,11,20));
        return invoice;
    }
    @Test void menuOffersUpcomingRoomsAndPaymentRemainsInInvoiceDetail() {
        open("LANDLORD_INVOICES");
        assertEquals(List.of("INVOICE_ADD","INVOICE_LIST_LATEST","INVOICE_LIST_DUE","MAIN_MENU"),actions());
    }
    @Test void listStartsWithOnlyTwoGroupsAndMainMenu() {
        open("INVOICE_LIST_LATEST");
        assertEquals(List.of("INVOICE_LIST_UNPAID","INVOICE_LIST_PAID","MAIN_MENU"),actions());
        verifyNoInteractions(service);
    }
    @Test void tenantsSeeOnlyScopedUnpaidInvoicesWithoutGroupSelectionOrPaymentControls() {
        owner.setRole(Role.MEMBER);
        var unpaid=bill(1,InvoiceStatus.UNPAID,"0");
        var paid=bill(2,InvoiceStatus.PAID,"100");
        when(service.getMemberInvoices(99L)).thenReturn(List.of(unpaid,paid));
        open("MEMBER_INVOICES");
        assertEquals(List.of("MAIN_MENU"),actions());
        verify(sender).sendMessageWithKeyboard(eq(99L),argThat(text -> text.contains("CHI TIẾT HÓA ĐƠN #1")
                && text.contains("Tiền phòng:") && text.contains("Tổng cộng:")
                && !text.contains("HÓA ĐƠN #2") && !text.contains("Chọn hóa đơn")),any());
        clearInvocations(sender);
        open("INVOICE_LIST_UNPAID");
        assertEquals(List.of("MAIN_MENU"),actions());
        clearInvocations(sender);
        open("INVOICE_LIST_PAID");
        assertEquals(List.of("MAIN_MENU"),actions());
        verify(service,never()).getAllInvoices();
        clearInvocations(sender);
        when(service.getInvoiceForUser(owner,1L)).thenReturn(unpaid);
        open("INVOICE_DETAIL_1");
        assertEquals(List.of("MAIN_MENU"),actions());
        clearInvocations(sender);
        open("INVOICE_PAYMENT_INIT_1");
        verify(sender).sendNotice(eq(99L),contains("không có quyền"));
        verify(sender,never()).sendMessageWithKeyboard(anyLong(),anyString(),any());
        unpaid.setPaidAmount(unpaid.getTotalAmount());
        clearInvocations(sender);
        open("MEMBER_INVOICES");
        assertEquals(List.of("MAIN_MENU"),actions());
        verify(sender).sendMessageWithKeyboard(eq(99L),contains("Không có hóa đơn chưa thanh toán"),any());
    }
    @Test void outstandingGroupIncludesPartialAndOverdueAndPaidBillsMoveAutomatically() {
        var unpaid=bill(1,InvoiceStatus.UNPAID,"0");
        var partial=bill(2,InvoiceStatus.PARTIALLY_PAID,"25");
        var overdue=bill(3,InvoiceStatus.OVERDUE,"0");
        var paid=bill(4,InvoiceStatus.PAID,"100");
        when(service.getAllInvoices()).thenReturn(List.of(unpaid,partial,overdue,paid));
        open("INVOICE_LIST_UNPAID");
        assertEquals(List.of("INVOICE_DETAIL_1","INVOICE_DETAIL_2","INVOICE_DETAIL_3","INVOICE_LIST_LATEST","MAIN_MENU"),actions());
        var label=keyboard().getKeyboard().get(1).get(0).getText();
        assertTrue(label.contains("Phòng 102")); assertTrue(label.contains(partial.getPeriodDisplay())); assertTrue(label.contains("Tổng cộng:"));
        unpaid.setPaidAmount(new BigDecimal("100"));
        clearInvocations(sender);
        open("INVOICE_LIST_PAID");
        assertEquals(List.of("INVOICE_DETAIL_1","INVOICE_DETAIL_4","INVOICE_LIST_LATEST","MAIN_MENU"),actions());
        assertFalse(keyboard().getKeyboard().get(0).get(0).getText().contains("Còn nợ:"));
        clearInvocations(sender);
        open("INVOICE_LIST_UNPAID");
        assertFalse(actions().contains("INVOICE_DETAIL_1"));
    }
    @Test void invoiceContentForBothRolesShowsOnlyTotalAndBinaryPaymentStatus() {
        var invoice=bill(1,InvoiceStatus.OVERDUE,"0");
        invoice.setNote("Người thuê trả phòng");
        when(service.getInvoiceForUser(owner,1L)).thenReturn(invoice);
        for(var role:List.of(Role.LANDLORD,Role.MEMBER)) {
            owner.setRole(role);
            for(var paid:List.of(false,true)) {
                invoice.setPaidAmount(paid?invoice.getTotalAmount():BigDecimal.ZERO);
                clearInvocations(sender);
                open("INVOICE_DETAIL_1");
                verify(sender).sendMessageWithKeyboard(eq(99L),argThat(text ->
                        text.contains("Tổng cộng:")
                        && text.contains(paid?"Trạng thái: 🟢 Đã thanh toán":"Trạng thái: 🔴 Chưa thanh toán")
                        && text.contains("Người thuê trả phòng")
                        && !text.contains("Đã thanh toán:") && !text.contains("Còn lại:")
                        && !text.contains("LỊCH SỬ THANH TOÁN") && !text.contains("Kỳ sử dụng:")),any());
            }
        }
    }
}
