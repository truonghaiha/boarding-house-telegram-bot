package com.boardinghousebot.bot.handler;
import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.meter.UtilityCycle;
import com.boardinghousebot.domain.user.*;
import com.boardinghousebot.service.*;
import com.boardinghousebot.state.*;
import com.boardinghousebot.state.UserConversationState.Step;
import com.boardinghousebot.util.*;
import com.boardinghousebot.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.*;
import java.util.*;
@Component
public class UtilityCycleFlow {
    private final UtilityCycleService service;
    private final ConversationStateService states;
    public UtilityCycleFlow(UtilityCycleService service,ConversationStateService states) { this.service=service; this.states=states; }
    private String money(BigDecimal n) { return n==null?"Chưa nhập":java.text.NumberFormat.getCurrencyInstance(new Locale("vi","VN")).format(n); }
    private String number(BigDecimal n) { return n==null?"Chưa nhập":n.stripTrailingZeros().toPlainString(); }
    private String date(LocalDate d) { return d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")); }
    private String periodDescription(UtilityCycle c) {
        var start=c.getContract().getStartDate();
        var firstEnd=start.plusMonths(1);
        if(c.getContract().getEndDate().isBefore(firstEnd)) firstEnd=c.getContract().getEndDate();
        return c.isBaseline()
                ? "Ngày bắt đầu: " + date(start)
                    + "\nKỳ hạn: " + date(start) + " – " + date(firstEnd)
                : "Kỳ sử dụng: " + c.periodLabel();
    }
    private InlineKeyboardRow row(String label,String callback) { return new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label,callback)); }
    public boolean meter(Long chat,User user,String action,UserConversationState state,BotMessageSender sender) {
        boolean member=user.getRole()==Role.MEMBER;
        if (!(action.startsWith("METER_CYCLE_") || action.startsWith("MEMBER_CYCLE_") || action.equals("METER_ADD")
                || action.equals("METER_LIST_ALL") || action.equals("METER_LIST_LATEST") || Set.of("MEMBER_METERS","MEMBER_METER_ALL").contains(action)
                || state.getStep().name().startsWith("CREATE_METER_CYCLE"))) return false;
        try {
            if (action.equals("/cancel")) {
                Long cycleId=(Long)state.getData().get("cycleId");
                if(cycleId!=null) service.cancelDraft(user.getTelegramId(),cycleId);
                states.clearState(user.getTelegramId());
                sender.sendMessageWithKeyboard(chat,"Đã hủy thao tác. Các chỉ số đã nhập được giữ nguyên.",TelegramKeyboardUtil.getLandlordMeterManagementMenu());
            } else if (member && action.equals("MEMBER_METERS")) currentMemberCycle(chat,user,sender);
            else if (action.equals("METER_ADD")) rooms(chat,user,sender);
            else if (action.equals("METER_LIST_ALL") || action.equals("METER_LIST_LATEST") || action.startsWith("METER_CYCLE_LIST_") || Set.of("MEMBER_METERS","MEMBER_METER_ALL").contains(action)) {
                String filter=action.startsWith("METER_CYCLE_LIST_")?action.substring("METER_CYCLE_LIST_".length()):"ACTIVE";
                list(chat,user,filter,state,sender);
            } else if (action.startsWith("METER_CYCLE_OPEN_")) {
                UtilityCycle c=service.open(user.getTelegramId(),Long.valueOf(action.substring("METER_CYCLE_OPEN_".length())));
                detail(chat,user,c,state,sender);
            } else if (action.startsWith("METER_CYCLE_NEXT_")) {
                var c=service.openContract(user.getTelegramId(),Long.valueOf(action.substring("METER_CYCLE_NEXT_".length())));
                detail(chat,user,c,state,sender);
            } else if (action.startsWith("METER_CYCLE_VIEW_")) {
                var c=service.get(user.getTelegramId(),Long.valueOf(action.substring("METER_CYCLE_VIEW_".length())));
                if(c.getContract().getStatus()!=com.boardinghousebot.domain.contract.ContractStatus.ACTIVE)
                    throw new BusinessException("Kỳ này thuộc hợp đồng cũ. Vui lòng mở lại danh sách kỳ của hợp đồng hiện tại.");
                states.clearState(user.getTelegramId());
                sender.sendMessageWithKeyboard(chat,"⚡💧 CHỈ SỐ ĐIỆN NƯỚC PHÒNG "+c.getContract().getRoom().getRoomNumber()
                        +"\nKỳ: "+c.periodLabel()
                        +"\n\n⚡ Điện\nSố bắt đầu: "+number(c.getPreviousElectricity())+" kWh"
                        +"\nSố kết thúc: "+number(c.getElectricity())+" kWh"
                        +"\n\n💧 Nước\nSố bắt đầu: "+number(c.getPreviousWater())+" m³"
                        +"\nSố kết thúc: "+number(c.getWater())+" m³",
                        new InlineKeyboardMarkup(List.of(
                                row("⬅️ Danh sách kỳ","METER_CYCLE_LIST_ROOM_"+c.getContract().getRoom().getId()),
                                row("⬅️ Menu chính","MAIN_MENU"))));
            } else if (action.startsWith("METER_CYCLE_DETAIL_") || action.startsWith("MEMBER_CYCLE_DETAIL_")) {
                String prefix=member?"MEMBER_CYCLE_DETAIL_":"METER_CYCLE_DETAIL_";
                detail(chat,user,service.get(user.getTelegramId(),Long.valueOf(action.substring(prefix.length()))),state,sender);
            } else if (action.startsWith("METER_CYCLE_SAVE_")) {
                Long id=Long.valueOf(action.substring("METER_CYCLE_SAVE_".length()));
                service.get(user.getTelegramId(),id);
                states.clearState(user.getTelegramId());
                sender.sendMessageWithKeyboard(chat,"💾 Đã lưu bản nháp điện nước. Đến ngày chốt hãy cập nhật chỉ số thực tế.",TelegramKeyboardUtil.getLandlordMeterManagementMenu());
            } else if (action.startsWith("METER_CYCLE_INPUT_")) {
                UtilityCycle c=service.get(user.getTelegramId(),Long.valueOf(action.substring("METER_CYCLE_INPUT_".length())));
                state.getData().remove("scheduledCloseCycleId");
                state.getData().put("cycleId",c.getId());
                state.setStep(Step.CREATE_METER_CYCLE_ELECTRIC); states.setState(user.getTelegramId(),state);
                sender.sendPrompt(chat,"⚡ " + (c.isBaseline()?"GHI CHỈ SỐ BAN ĐẦU":"GHI CHỈ SỐ CUỐI KỲ")
                        + " PHÒNG "+c.getContract().getRoom().getRoomNumber()+"\n"+periodDescription(c)
                        + (c.isBaseline()?"":"\nChỉ số điện đầu kỳ: "+number(c.getPreviousElectricity())+" kWh")
                        + "\n\nNhập chỉ số điện "+(c.isBaseline()?"ban đầu":"mới")+" (kWh):");
            } else if (action.startsWith("METER_CYCLE_DATE_")) {
                throw new BusinessException("Chức năng sửa ngày đọc đã được bỏ. Vui lòng mở lại chi tiết kỳ điện nước.");
            } else if (action.startsWith("METER_CYCLE_CHECKOUT_") || action.startsWith("METER_CYCLE_ADJUST_")) {
                boolean checkout=action.startsWith("METER_CYCLE_CHECKOUT_");
                String prefix=checkout?"METER_CYCLE_CHECKOUT_":"METER_CYCLE_ADJUST_";
                Long cycleId=Long.valueOf(action.substring(prefix.length()));
                if(checkout) {
                    var c=service.get(user.getTelegramId(),cycleId);
                    if(c.getElectricity()==null || c.getWater()==null) throw new BusinessException("Vui lòng ghi đủ chỉ số điện và nước cuối kỳ trước khi chốt.");
                    state.getData().remove("scheduledCloseCycleId");
                    detail(chat,user,service.adjust(user.getTelegramId(),cycleId,null,true),state,sender);
                    return true;
                }
                state.getData().put("cycleId",cycleId);
                state.getData().put("cycleCheckout",false);
                state.setStep(Step.CREATE_METER_CYCLE_RENT); states.setState(user.getTelegramId(),state);
                sender.sendPrompt(chat,(checkout?"🚪 CHỐT KHI TRẢ PHÒNG":"📅 CHỐT THEO NGÀY ĐỌC THỰC TẾ")
                        +"\n\nNhập tiền phòng đã thỏa thuận cho kỳ này (VNĐ, có thể bằng 0). Bot không tự chia tiền phòng theo ngày."
                        +"\nNgày đọc đang lưu sẽ là ngày kết thúc kỳ; hãy kiểm tra ngày trước khi xác nhận.");
            } else if (action.startsWith("METER_CYCLE_PERIOD_")) {
                Long id=Long.valueOf(action.substring("METER_CYCLE_PERIOD_".length()));
                requireState(state,id,Step.CREATE_METER_CYCLE_CONFIRM);
                var c=service.get(user.getTelegramId(),id);
                if(c.isBaseline() || c.isFinalized()) throw new BusinessException("Vui lòng mở lại kỳ điện nước cần chốt.");
                if(c.getElectricity()==null || c.getWater()==null) throw new BusinessException("Vui lòng ghi đủ chỉ số điện và nước cuối kỳ trước khi chốt.");
                service.cancelDraft(user.getTelegramId(),id);
                state.getData().put("scheduledCloseCycleId",id);
                detail(chat,user,service.get(user.getTelegramId(),id),state,sender);
            } else if (action.startsWith("METER_CYCLE_FINALIZE_")) {
                Long id=Long.valueOf(action.substring("METER_CYCLE_FINALIZE_".length()));
                requireState(state,id,Step.CREATE_METER_CYCLE_CONFIRM);
                boolean scheduled=id.equals(state.getData().get("scheduledCloseCycleId"));
                Long version=(Long)state.getData().get("cycleVersion");
                var current=service.get(user.getTelegramId(),id);
                var c=current.isBaseline()?service.finalizeCycle(user.getTelegramId(),id,version)
                        :service.confirmAndInvoice(user.getTelegramId(),id,version,scheduled);
                if(c.isBaseline() && c.getContract().getEndDate().isAfter(c.getPeriodEnd())) {
                    var first=service.openContract(user.getTelegramId(),c.getContract().getId());
                    states.clearState(user.getTelegramId());
                    sender.sendMessageWithKeyboard(chat,"✅ ĐÃ LƯU CHỈ SỐ BAN ĐẦU PHÒNG "+c.getContract().getRoom().getRoomNumber()
                            +"\n\nNgày bắt đầu: "+date(first.getPeriodStart())
                            +"\nKỳ hạn: "+first.periodLabel()
                            +"\n\n⚡ Điện đầu kỳ: "+number(c.getElectricity())+" kWh"
                            +"\n💧 Nước đầu kỳ: "+number(c.getWater())+" m³"
                            +"\n\nĐến ngày "+date(first.getPlannedEnd())+", hãy ghi chỉ số cuối kỳ để tính tiền điện nước.",
                            new InlineKeyboardMarkup(List.of(
                                    row("✏️ Ghi chỉ số cuối kỳ","METER_CYCLE_INPUT_"+first.getId()),
                                    row("📋 Danh sách chỉ số","METER_LIST_ALL"),
                                    row("⬅️ Menu chính","MAIN_MENU"))));
                    return true;
                }
                sender.sendNotice(chat,c.isBaseline()?"✅ Đã lưu chỉ số ban đầu. Chưa phát sinh tiền điện nước.":
                        c.isCheckout()?"✅ Đã tạo hóa đơn trả phòng. Hợp đồng sẽ kết thúc khi thanh toán đủ hóa đơn này và các khoản nợ trước đó.":"✅ Đã chốt kỳ và tạo hóa đơn.");
                detail(chat,user,c,state,sender);
            } else if (state.getStep()==Step.CREATE_METER_CYCLE_ELECTRIC || state.getStep()==Step.CREATE_METER_CYCLE_WATER) {
                Long id=(Long)state.getData().get("cycleId");
                boolean electric=state.getStep()==Step.CREATE_METER_CYCLE_ELECTRIC;
                UtilityCycle c=service.reading(user.getTelegramId(),id,electric,new BigDecimal(action.trim()));
                if (electric) {
                    state.setStep(Step.CREATE_METER_CYCLE_WATER); states.setState(user.getTelegramId(),state);
                    sender.sendPrompt(chat,(c.isBaseline()?"":"Chỉ số nước đầu kỳ: "+number(c.getPreviousWater())+" m³\n\n")
                            +"💧 Nhập chỉ số nước "+(c.isBaseline()?"ban đầu":"mới")+" (m³):");
                } else detail(chat,user,c,state,sender);
            } else if (state.getStep()==Step.CREATE_METER_CYCLE_DATE) {
                states.clearState(user.getTelegramId());
                throw new BusinessException("Chức năng sửa ngày đọc đã được bỏ. Vui lòng mở lại chi tiết kỳ điện nước.");
            } else if (state.getStep()==Step.CREATE_METER_CYCLE_RENT) {
                detail(chat,user,service.adjust(user.getTelegramId(),(Long)state.getData().get("cycleId"),new BigDecimal(action.trim()),
                        Boolean.TRUE.equals(state.getData().get("cycleCheckout"))),state,sender);
            } else sender.sendNotice(chat,"Vui lòng dùng các nút bên dưới hoặc Hủy.");
        } catch(NumberFormatException|DateTimeException e) { sender.sendNotice(chat,"❌ Dữ liệu không hợp lệ. Vui lòng kiểm tra số tiền/chỉ số hoặc ngày theo dd/MM/yyyy."); }
        catch(BusinessException e) { sender.sendNotice(chat,"❌ "+e.getMessage()); }
        return true;
    }
    private void requireState(UserConversationState state,Long id,Step step) {
        if(state.getStep()!=step || !id.equals(state.getData().get("cycleId"))) throw new BusinessException("Thao tác đã hết hiệu lực. Vui lòng mở lại kỳ.");
    }
    private void rooms(Long chat,User user,BotMessageSender sender) { rooms(chat,user,sender,false); }
    private void rooms(Long chat,User user,BotMessageSender sender,boolean invoiceEntry) {
        states.clearState(user.getTelegramId());
        var all=service.list(user.getTelegramId()); var rows=new ArrayList<InlineKeyboardRow>();
        for(var k:service.activeContracts(user.getTelegramId())) {
            var latest=all.stream().filter(c -> c.getContract().getId().equals(k.getId())).findFirst();
            String status=latest.map(this::status).orElse("Chưa ghi số ban đầu");
            var existing=invoiceEntry?latest.filter(c -> c.isFinalized() && !c.isBaseline()
                    && (c.isCheckout() || !c.getPeriodEnd().isBefore(BillingDates.today()) || !k.getEndDate().isAfter(c.getPeriodEnd())))
                    .flatMap(c -> service.invoiceId(c.getId())):Optional.<Long>empty();
            rows.add(row("🏠 Phòng "+k.getRoom().getRoomNumber()+" · "+(invoiceEntry && existing.isPresent()?"Xem hóa đơn":status),
                    invoiceEntry && existing.isPresent()?"INVOICE_DETAIL_"+existing.get():"METER_CYCLE_OPEN_"+k.getRoom().getId()));
        }
        rows.add(row(invoiceEntry?"🔙 Hóa đơn":"🔙 Điện nước",invoiceEntry?"LANDLORD_INVOICES":"LANDLORD_METERS"));
        String text=rows.size()==1
                ? "Chưa có phòng có hợp đồng đang hiệu lực. Hãy tạo hợp đồng trước."
                : invoiceEntry?"💰 TẠO HÓA ĐƠN\n\nChọn phòng để ghi chỉ số và chốt tiền điện nước. Hóa đơn được tạo tự động sau khi xác nhận chốt.":"⚡💧 CHỌN PHÒNG GHI SỐ & CHỐT TIỀN\n\nChọn phòng để nhập chỉ số điện, nước và chốt tiền theo kỳ hoặc khi trả phòng sớm.";
        sender.sendMessageWithKeyboard(chat,text,new InlineKeyboardMarkup(rows));
    }
    private String status(UtilityCycle c) {
        if(c.isBaseline()) {
            if(c.isFinalized()) return "Chưa ghi số cuối kỳ";
            return c.getElectricity()!=null && c.getWater()!=null
                    ? "Đã nhập đủ · Chờ xác nhận lưu" : "Chưa ghi đủ số ban đầu";
        }
        if(c.isFinalized()) return c.isCheckout() && c.getContract().getStatus()==com.boardinghousebot.domain.contract.ContractStatus.ACTIVE
                ?"Đã chốt trả phòng · Chờ thanh toán":serviceHasInvoice(c)?"Đã có hóa đơn":"Đã ghi · Chưa có hóa đơn";
        return c.getElectricity()==null && c.getWater()==null?"Chưa ghi số cuối kỳ":"Đang nhập dở";
    }
    private boolean serviceHasInvoice(UtilityCycle c) { return service.invoiceId(c.getId()).isPresent(); }
    private void currentMemberCycle(Long chat,User user,BotMessageSender sender) {
        states.clearState(user.getTelegramId());
        var current=service.currentForMember(user.getTelegramId());
        String text="⚡💧 ĐIỆN NƯỚC KỲ HIỆN TẠI\n\n";
        if(current.isEmpty()) text+="Chưa có kỳ điện nước được phép xem.";
        else {
            var c=current.get();
            text+="🏠 Phòng: "+c.roomNumber()+"\nKỳ: "+date(c.start())+" – "+date(c.end())
                    +"\n\n⚡ Điện\nChỉ số đầu kỳ: "+number(c.electricityStart())+" kWh"
                    +"\nChỉ số cuối kỳ: "+(c.electricityEnd()==null?"Chưa chốt":number(c.electricityEnd())+" kWh")
                    +"\n\n💧 Nước\nChỉ số đầu kỳ: "+number(c.waterStart())+" m³"
                    +"\nChỉ số cuối kỳ: "+(c.waterEnd()==null?"Chưa chốt":number(c.waterEnd())+" m³")
                    +"\n\nTình trạng: "+(c.finalized()?"Đã chốt kỳ":"Chưa chốt kỳ, chưa phát sinh tiền điện nước.");
        }
        sender.sendMessageWithKeyboard(chat,text,new InlineKeyboardMarkup(List.of(row("⬅️ Menu chính","MAIN_MENU"))));
    }
    private void list(Long chat,User user,String filter,UserConversationState state,BotMessageSender sender) {
        states.clearState(user.getTelegramId());
        var cycles=service.list(user.getTelegramId()); var rows=new ArrayList<InlineKeyboardRow>();
        boolean owner=user.getRole()==Role.LANDLORD;
        if(owner && !filter.startsWith("ROOM_")) {
            var rooms=new TreeMap<String,Long>();
            for(var c:cycles) if(c.getContract().getStatus()==com.boardinghousebot.domain.contract.ContractStatus.ACTIVE)
                rooms.put(c.getContract().getRoom().getRoomNumber(),c.getContract().getRoom().getId());
            for(var k:service.activeContracts(user.getTelegramId())) rooms.put(k.getRoom().getRoomNumber(),k.getRoom().getId());
            rooms.forEach((name,id) -> rows.add(row("🏠 Phòng "+name,"METER_CYCLE_LIST_ROOM_"+id)));
            String text=rooms.isEmpty()?"Chưa có phòng để xem chỉ số.":"📋 DANH SÁCH CHỈ SỐ\n\nChọn phòng để xem các kỳ điện nước.";
            rows.add(row("⬅️ Điện nước","LANDLORD_METERS"));
            rows.add(row("⬅️ Menu chính","MAIN_MENU"));
            sender.sendMessageWithKeyboard(chat,text,new InlineKeyboardMarkup(rows)); return;
        }
        Long roomId=owner?Long.valueOf(filter.substring("ROOM_".length())):null;
        StringBuilder text=new StringBuilder("📋 DANH SÁCH KỲ ĐIỆN NƯỚC\n\n");
        int periodIndex=0;
        for(var c:cycles) {
            if(owner && c.isBaseline()) continue;
            if(owner && c.getContract().getStatus()!=com.boardinghousebot.domain.contract.ContractStatus.ACTIVE) continue;
            if(roomId!=null && !roomId.equals(c.getContract().getRoom().getId())) continue;
            String label=periodIndex==0?"Kỳ này":periodIndex==1?"Kỳ trước":"Kỳ cũ";
            String name=(owner?label:"Phòng "+c.getContract().getRoom().getRoomNumber())+" · "
                    +(c.isBaseline()?"Chỉ số ban đầu · "+date(c.getPeriodStart()):c.periodLabel());
            rows.add(row(name,(owner?"METER_CYCLE_VIEW_":"MEMBER_CYCLE_DETAIL_")+c.getId()));
            periodIndex++;
        }
        text.append(rows.isEmpty()?"Chưa có kỳ điện nước.":"Chọn một kỳ để xem chi tiết.");
        if(owner) rows.add(row("⬅️ Danh sách phòng","METER_LIST_ALL"));
        rows.add(row("⬅️ Menu chính","MAIN_MENU"));
        sender.sendMessageWithKeyboard(chat,text.toString(),new InlineKeyboardMarkup(rows));
    }
    private void detail(Long chat,User user,UtilityCycle c,UserConversationState state,BotMessageSender sender) {
        boolean owner=user.getRole()==Role.LANDLORD;
        String text="⚡💧 "+(c.isBaseline()?"CHỈ SỐ BAN ĐẦU":"ĐIỆN NƯỚC THEO KỲ")+" PHÒNG "+c.getContract().getRoom().getRoomNumber()
                +"\n"+periodDescription(c);
        if(c.isBaseline()) {
            if(c.getElectricity()!=null || c.getWater()!=null) text+=
                    "\n\nChỉ số điện ban đầu: "+number(c.getElectricity())+" kWh"
                    +"\nChỉ số nước ban đầu: "+number(c.getWater())+" m³"
                    +"\nTình trạng: "+(!owner && c.isFinalized()?"Đã xác nhận chỉ số ban đầu":status(c));
            if(!owner) text+="\n\nMốc ban đầu chưa có chỉ số cuối kỳ, chưa phát sinh tiền điện nước.";
        } else text+="\n\n⚡ Điện"
                +"\nChỉ số đầu kỳ: "+number(c.getPreviousElectricity())+" kWh"
                +"\nChỉ số cuối kỳ: "+(c.getElectricity()==null?"Chưa nhập":number(c.getElectricity())+" kWh")
                +"\n\n💧 Nước"
                +"\nChỉ số đầu kỳ: "+number(c.getPreviousWater())+" m³"
                +"\nChỉ số cuối kỳ: "+(c.getWater()==null?"Chưa nhập":number(c.getWater())+" m³")
                +"\n\nTình trạng: "+status(c);
        if(c.isFinalized() && !c.isBaseline()) text+="\n\n⚡ Tiêu thụ: "+number(c.getElectricity().subtract(c.getPreviousElectricity()))+" kWh x "+money(c.getElectricityPrice())+"/kWh = "+money(c.electricityAmount())
                +"\n💧 Tiêu thụ: "+number(c.getWater().subtract(c.getPreviousWater()))+" m³ x "+money(c.getWaterPrice())+"/m³ = "+money(c.waterAmount());
        if(!c.isBaseline() && !c.isFinalized() && c.getElectricity()!=null && c.getWater()!=null) {
            try {
                var estimate=service.estimate(user.getTelegramId(),c.getId());
                text+="\n\n⚡ Tiêu thụ: "+number(c.getElectricity().subtract(c.getPreviousElectricity()))+" kWh x "+money(estimate.electricityPrice())+"/kWh = "+money(estimate.electricityAmount())
                        +"\n💧 Tiêu thụ: "+number(c.getWater().subtract(c.getPreviousWater()))+" m³ x "+money(estimate.waterPrice())+"/m³ = "+money(estimate.waterAmount())
                        +"\nTổng điện nước dự kiến: "+money(estimate.electricityAmount().add(estimate.waterAmount()));
            } catch(BusinessException e) { text+="\n"+e.getMessage(); }
        }
        if(!c.isBaseline()) {
            if(c.isFinalized()) text+="\nTổng điện nước: "+money(c.electricityAmount().add(c.waterAmount()));
            if(c.isFinalized() || c.isAdjusted() || c.getId().equals(state.getData().get("scheduledCloseCycleId")))
                text+="\nTiền phòng: "+money(c.getRent());
            text+="\n\n"+(c.isFinalized() && !c.isAdjusted()
                    ? "Ngày chốt theo kỳ: "+date(c.getPeriodEnd())
                    : "Ngày chốt dự kiến: "+date(c.getPlannedEnd()));
            if(c.isAdjusted()) text+="\nNgày chốt thực tế: "+date(c.getReadingDate());
        }
        if(owner && c.isBaseline() && !c.isFinalized() && c.getElectricity()==null && c.getWater()==null) {
            state.getData().remove("scheduledCloseCycleId");
            state.getData().put("cycleId",c.getId());
            state.setStep(Step.CREATE_METER_CYCLE_ELECTRIC);
            states.setState(user.getTelegramId(),state);
            sender.sendMessageWithKeyboard(chat,text+"\n\nNhập chỉ số điện, nước lúc bắt đầu thuê phòng."
                    +"\n\nNhập chỉ số điện ban đầu (kWh):",TelegramKeyboardUtil.getCancelKeyboard());
            return;
        }
        var rows=new ArrayList<InlineKeyboardRow>();
        var invoice=service.invoiceId(c.getId());
        if(owner && invoice.isEmpty()) {
            rows.add(row(c.isFinalized()?"✏️ Sửa chỉ số":c.isBaseline()?"✏️ Nhập chỉ số ban đầu":"✏️ Ghi số cuối kỳ","METER_CYCLE_INPUT_"+c.getId()));
            if(!c.isFinalized()) {
                if(!c.isBaseline()) { rows.add(row("🚪 Chốt trả phòng sớm","METER_CYCLE_CHECKOUT_"+c.getId()));
                    rows.add(row("📅 Chốt theo kỳ","METER_CYCLE_PERIOD_"+c.getId())); }
                if(c.getElectricity()!=null && c.getWater()!=null) {
                    if(c.isBaseline() || c.isCheckout())
                        rows.add(row(c.isBaseline()?"✅ Lưu số ban đầu":"✅ Xác nhận chốt kỳ","METER_CYCLE_FINALIZE_"+c.getId()));
                }
            }
            if(c.isFinalized() && !c.isBaseline()) rows.add(row("💰 Tạo hóa đơn","INVOICE_ADD"));
        }
        if(invoice.isPresent()) rows.add(row("💰 Xem hóa đơn","INVOICE_DETAIL_"+invoice.get()));
        if(owner && c.isFinalized() && !c.isCheckout()) rows.add(row("➕ Ghi kỳ tiếp theo","METER_CYCLE_NEXT_"+c.getContract().getId()));
        rows.add(row("📋 Danh sách chỉ số",owner?"METER_CYCLE_LIST_ROOM_"+c.getContract().getRoom().getId():"MEMBER_METERS"));
        if(owner && !c.isFinalized()) {
            if(c.isCheckout() || c.getId().equals(state.getData().get("scheduledCloseCycleId"))) {
                text+=c.isCheckout()?"\n\nXác nhận sẽ tạo hóa đơn trả phòng. Hợp đồng kết thúc sau khi thanh toán đủ mọi hóa đơn."
                        :"\n\nXác nhận sẽ chốt kỳ và tạo hóa đơn.";
                rows.clear();
                rows.add(row("✅ Xác nhận chốt kỳ","METER_CYCLE_FINALIZE_"+c.getId()));
            } else if(c.getElectricity()!=null && c.getWater()!=null
                    && (c.isBaseline() || state.getStep()==Step.CREATE_METER_CYCLE_WATER)) {
                rows.clear();
                rows.add(row("✅ Xác nhận lưu",(c.isBaseline()?"METER_CYCLE_FINALIZE_":"METER_CYCLE_SAVE_")+c.getId()));
            }
            state.setStep(Step.CREATE_METER_CYCLE_CONFIRM); state.getData().put("cycleId",c.getId()); state.getData().put("cycleVersion",c.getVersion());
            states.setState(user.getTelegramId(),state); rows.add(row("❌ Hủy","/cancel"));
        } else states.clearState(user.getTelegramId());
        sender.sendMessageWithKeyboard(chat,text,new InlineKeyboardMarkup(rows));
    }
    public boolean invoice(Long chat,User user,String action,UserConversationState state,BotMessageSender sender) {
        if(!(action.equals("INVOICE_ADD") || action.equals("INVOICE_LIST_DUE") || action.startsWith("INVOICE_CYCLE_") || state.getStep()==Step.CREATE_INVOICE_CYCLE_CONFIRM)) return false;
        if(user.getRole()!=Role.LANDLORD) sender.sendNotice(chat,"❌ Bạn không có quyền lập hóa đơn.");
        else if(action.equals("INVOICE_LIST_DUE")) {
            states.clearState(user.getTelegramId());
            var dueRooms=service.upcomingInvoiceRooms(user.getTelegramId());
            var rows=new ArrayList<InlineKeyboardRow>();
            var today=BillingDates.today();
            for(var room:dueRooms) {
                long days=java.time.temporal.ChronoUnit.DAYS.between(today,room.dueDate());
                String status=days<0?"⚠️ Trễ "+(-days)+" ngày":days==0?"🔔 Hôm nay":"Còn "+days+" ngày";
                rows.add(row("🏠 Phòng "+room.roomNumber()+" · "+date(room.dueDate())+" · "+status,
                        room.cycleId()==null?"METER_CYCLE_OPEN_"+room.roomId():"METER_CYCLE_DETAIL_"+room.cycleId()));
            }
            rows.add(row("⬅️ Hóa đơn","LANDLORD_INVOICES"));
            sender.sendMessageWithKeyboard(chat,"📅 PHÒNG SẮP ĐẾN KỲ\n\n"
                    +(dueRooms.isEmpty()?"Không có phòng đến hạn trong 3 ngày tới hoặc quá hạn chưa lập hóa đơn."
                    :"Các phòng đến hạn trong 3 ngày tới và quá hạn chưa lập hóa đơn.\nƯu tiên phòng quá hạn trước. Chọn phòng để kiểm tra và chốt chỉ số điện, nước."),
                    new InlineKeyboardMarkup(rows));
        }
        else rooms(chat,user,sender,true);
        return true;
    }
}
