package com.boardinghousebot.service;
import com.boardinghousebot.domain.meter.*;
import com.boardinghousebot.domain.contract.*;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.user.*;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.repository.*;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.util.BillingDates;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
@Service
@Transactional
public class UtilityCycleService {
    private final UtilityCycleRepository cycles;
    private final ContractRepository contracts;
    private final InvoiceRepository invoices;
    private final UserRepository users;
    private final AuthorizationService auth;
    private final UtilityRateService prices;
    private final RoomStayService stays;
    private final NotificationService notifications;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final AuditLogService audit;
    private final CheckoutSettlementService checkoutSettlement;
    public UtilityCycleService(UtilityCycleRepository cycles, ContractRepository contracts, InvoiceRepository invoices,
            UserRepository users, AuthorizationService auth, UtilityRateService prices, RoomStayService stays,
            NotificationService notifications, org.springframework.context.ApplicationEventPublisher events, AuditLogService audit,
            CheckoutSettlementService checkoutSettlement) {
        this.checkoutSettlement=checkoutSettlement;
        this.cycles=cycles; this.contracts=contracts; this.invoices=invoices; this.users=users;
        this.auth=auth; this.prices=prices; this.stays=stays; this.notifications=notifications; this.events=events; this.audit=audit;
    }
    private void landlord(Long id) { auth.requireLandlord(users.findByTelegramId(id).orElseThrow(() -> error("Không tìm thấy người dùng."))); }
    private BusinessException error(String text) { return new BusinessException(text); }
    public List<Contract> activeContracts(Long actor) {
        landlord(actor);
        return contracts.findAllWithDetails().stream().filter(c -> c.getStatus()==ContractStatus.ACTIVE).toList();
    }
    public record DueRoom(Long roomId,String roomNumber,LocalDate dueDate,Long cycleId) {}
    @Transactional(readOnly=true)
    public List<DueRoom> upcomingInvoiceRooms(Long actor) {
        landlord(actor);
        var latest=new HashMap<Long,UtilityCycle>();
        for(var c:cycles.allDetails())
            latest.merge(c.getContract().getId(),c,(a,b) -> a.getId()>b.getId()?a:b);
        var result=new ArrayList<DueRoom>();
        var cutoff=BillingDates.today().plusDays(3);
        for(var c:latest.values()) {
            var contract=c.getContract();
            if(contract.getStatus()!=ContractStatus.ACTIVE || c.isCheckout() && c.isFinalized()
                    || c.isBaseline() && !c.isFinalized()) continue;
            LocalDate due;
            Long cycleId=null;
            if(!c.isBaseline() && (!c.isFinalized() || !invoices.existsByUtilityCycleId(c.getId()))) {
                due=c.getPlannedEnd();
                cycleId=c.getId();
            } else {
                if(!contract.getEndDate().isAfter(c.getPeriodEnd())) continue;
                due=BillingDates.next(contract.getStartDate(),c.getPeriodEnd());
                if(contract.getEndDate().isBefore(due)) due=contract.getEndDate();
            }
            if(due!=null && !due.isAfter(cutoff))
                result.add(new DueRoom(contract.getRoom().getId(),contract.getRoom().getRoomNumber(),due,cycleId));
        }
        result.sort(Comparator.comparing(DueRoom::dueDate).thenComparing(DueRoom::roomNumber));
        return result;
    }
    public UtilityCycle get(Long actor, Long id) {
        var user=users.findByTelegramId(actor).orElseThrow(() -> error("Không tìm thấy người dùng."));
        var c=cycles.details(id).orElseThrow(() -> error("Không tìm thấy kỳ điện nước."));
        if (user.getRole()!=Role.LANDLORD) {
            auth.requireMember(user);
            if (!c.isFinalized() || !canRead(user,c)) throw error("Bạn không có quyền xem kỳ điện nước này.");
        }
        if(c.isBaseline() && !c.isFinalized()) {
            var start=c.getContract().getStartDate();
            c.setPeriodStart(start); c.setPeriodEnd(start); c.setPlannedEnd(start); c.setReadingDate(start);
        }
        return c;
    }
    private boolean canRead(User user, UtilityCycle c) {
        return user.getTelegramId().equals(c.getContract().getMember().getTelegramId())
                || stays.canReadInterval(user,c.getContract().getRoom().getId(),c.getPeriodStart(),c.getPeriodEnd(),c.getFinalizedAt()!=null?c.getFinalizedAt():c.getCreatedAt());
    }
    public List<UtilityCycle> list(Long actor) {
        var user=users.findByTelegramId(actor).orElseThrow(() -> error("Không tìm thấy người dùng."));
        if (user.getRole()==Role.LANDLORD) return cycles.allDetails();
        auth.requireMember(user);
        return cycles.allDetails().stream().filter(c -> c.isFinalized() && canRead(user,c)).toList();
    }
    public record MemberCycleView(String roomNumber,LocalDate start,LocalDate end,
            BigDecimal electricityStart,BigDecimal electricityEnd,BigDecimal waterStart,BigDecimal waterEnd,boolean finalized) {}
    @Transactional(readOnly=true)
    public Optional<MemberCycleView> currentForMember(Long actor) {
        var user=users.findByTelegramId(actor).orElseThrow(() -> error("Không tìm thấy người dùng."));
        auth.requireMember(user);
        var residence=stays.getCurrentResidence(actor);
        if(!residence.isRenting()) return Optional.empty();
        var latest=cycles.allDetails().stream()
                .filter(c -> c.getContract().getStatus()==ContractStatus.ACTIVE
                        && c.getContract().getRoom().getId().equals(residence.room().getId()))
                .max(Comparator.comparing(UtilityCycle::getId));
        if(latest.isEmpty()) return Optional.empty();
        var c=latest.get();
        if((c.isBaseline() && !c.isFinalized()) || !canRead(user,c)) return Optional.empty();
        if(c.isBaseline()) {
            var end=BillingDates.next(c.getContract().getStartDate(),c.getPeriodStart());
            if(c.getContract().getEndDate().isBefore(end)) end=c.getContract().getEndDate();
            return Optional.of(new MemberCycleView(c.getContract().getRoom().getRoomNumber(),c.getPeriodStart(),end,
                    c.getElectricity(),null,c.getWater(),null,false));
        }
        // Only confirmed starting readings are public while the landlord is entering the ending readings.
        return Optional.of(new MemberCycleView(c.getContract().getRoom().getRoomNumber(),c.getPeriodStart(),
                c.isFinalized()?c.getPeriodEnd():c.getPlannedEnd(),c.getPreviousElectricity(),
                c.isFinalized()?c.getElectricity():null,c.getPreviousWater(),c.isFinalized()?c.getWater():null,c.isFinalized()));
    }
    public UtilityCycle open(Long actor, Long roomId) {
        landlord(actor);
        Contract contract=contracts.findActiveContractByRoomId(roomId).orElseThrow(() -> error("Phòng chưa có hợp đồng đang hiệu lực. Vui lòng tạo hợp đồng trước."));
        return openContract(actor,contract.getId());
    }
    public UtilityCycle openContract(Long actor,Long contractId) {
        landlord(actor);
        Contract contract=contracts.findByIdWithDetails(contractId).orElseThrow(() -> error("Không tìm thấy hợp đồng."));
        var history=cycles.findByContractIdOrderByIdDesc(contract.getId());
        var draft=history.stream().filter(c -> !c.isFinalized()).findFirst();
        if (draft.isPresent()) return get(actor,draft.get().getId());
        var c=new UtilityCycle(); c.setContract(contract);
        var last=history.stream().filter(UtilityCycle::isFinalized).findFirst();
        if (last.isEmpty()) {
            c.setBaseline(true); c.setPeriodStart(contract.getStartDate()); c.setPeriodEnd(contract.getStartDate());
            c.setPlannedEnd(contract.getStartDate());
        } else {
            var previous=last.get();
            if (previous.isCheckout()) throw error("Đợt thuê này đã chốt trả phòng. Vui lòng kiểm tra hợp đồng.");
            c.setPeriodStart(previous.getPeriodEnd());
            if(c.getPeriodStart().isBefore(contract.getStartDate())) throw error("Ngày bắt đầu hợp đồng đã thay đổi. Vui lòng kiểm tra lại mốc chỉ số ban đầu trước khi mở kỳ.");
            LocalDate end=BillingDates.next(contract.getStartDate(),c.getPeriodStart());
            if (!contract.getEndDate().isAfter(c.getPeriodStart())) throw error("Đã ghi hết kỳ của hợp đồng. Vui lòng gia hạn hoặc kết thúc hợp đồng.");
            if (contract.getEndDate().isBefore(end)) end=contract.getEndDate();
            c.setPeriodEnd(end); c.setPlannedEnd(end);
            c.setPreviousElectricity(previous.getElectricity()); c.setPreviousWater(previous.getWater());
            c.setRent(contract.getMonthlyRent());
            c.setElectricityPrice(previous.isBaseline() && previous.getElectricityPrice()!=null ? previous.getElectricityPrice()
                    : prices.at(UtilityType.ELECTRICITY,c.getPeriodStart()).map(UtilityRate::getUnitPrice).orElse(null));
            c.setWaterPrice(previous.isBaseline() && previous.getWaterPrice()!=null ? previous.getWaterPrice()
                    : prices.at(UtilityType.WATER,c.getPeriodStart()).map(UtilityRate::getUnitPrice).orElse(null));
        }
        c.setReadingDate(c.isBaseline()?contract.getStartDate():BillingDates.today());
        return cycles.saveAndFlush(c);
    }
    private UtilityCycle editable(Long actor, Long id) {
        landlord(actor); var c=get(actor,id);
        if (invoices.existsByUtilityCycleId(id)) throw error("Kỳ này đã phát hành hóa đơn, không thể sửa trực tiếp.");
        if (c.isFinalized() && cycles.findByContractIdOrderByIdDesc(c.getContract().getId()).stream().anyMatch(n -> n.getId()>id))
            throw error("Kỳ tiếp theo đã được mở. Không thể sửa số đầu kỳ của kỳ tiếp theo.");
        return c;
    }
    public void cancelDraft(Long actor, Long id) {
        landlord(actor);
        var c=get(actor,id);
        if(c.isFinalized() || c.getFinalizedAt()!=null) return;
        if(invoices.existsByUtilityCycleId(id)) throw error("Kỳ này đã có hóa đơn, không thể hủy.");
        if(!c.isBaseline()) {
            c.setCheckout(false);
            c.setAdjusted(false);
            c.setPeriodEnd(c.getPlannedEnd());
            c.setRent(c.getContract().getMonthlyRent());
            cycles.saveAndFlush(c);
        }
    }
    public UtilityCycle reading(Long actor, Long id, boolean electricity, BigDecimal value) {
        var c=editable(actor,id);
        if (value==null || value.signum()<0 || value.scale()>2 || value.precision()>12) throw error("Chỉ số phải không âm, tối đa 2 chữ số thập phân.");
        BigDecimal previous=electricity?c.getPreviousElectricity():c.getPreviousWater();
        if (previous!=null && value.compareTo(previous)<0) throw error("Chỉ số mới không được nhỏ hơn chỉ số đầu kỳ. Nếu thay đồng hồ, cần kiểm tra trước khi chốt.");
        if (electricity) c.setElectricity(value); else c.setWater(value);
        c.setReadingDate(c.isBaseline()?c.getContract().getStartDate():BillingDates.today());
        c.setFinalized(false);
        return cycles.saveAndFlush(c);
    }
    public UtilityCycle adjust(Long actor,Long id,BigDecimal agreedRent,boolean checkout) {
        var c=editable(actor,id);
        if (c.isBaseline()) throw error("Cần ghi số ban đầu trước khi chốt trả phòng.");
        if(!checkout && c.getReadingDate().isBefore(c.getPlannedEnd()))
            throw error("Chưa đến hạn chốt. Nếu trả phòng trước hạn, hãy chọn Chốt khi trả phòng.");
        if(checkout && c.getReadingDate().isAfter(c.getPlannedEnd()))
            throw error("Ngày trả phòng đã qua ngày chốt kỳ. Hãy kiểm tra kỳ trước hoặc chốt theo ngày thực tế với tiền phòng đã thỏa thuận.");
        if (checkout) agreedRent=c.getContract().getMonthlyRent();
        if (agreedRent==null || agreedRent.signum()<0 || agreedRent.scale()>2 || agreedRent.precision()>12) throw error("Tiền phòng phải không âm, tối đa 2 chữ số thập phân.");
        c.setCheckout(checkout); c.setAdjusted(true); c.setRent(agreedRent); c.setFinalized(false); return cycles.saveAndFlush(c);
    }
    public UtilityCycle finalizeCycle(Long actor,Long id,Long version) {
        return finalizeCycle(actor,id,version,false);
    }
    public UtilityCycle finalizePeriod(Long actor,Long id,Long version) {
        return finalizeCycle(actor,id,version,true);
    }
    public UtilityCycle confirmAndInvoice(Long actor,Long id,Long version,boolean scheduled) {
        var c=finalizeCycle(actor,id,version,scheduled);
        if(!c.isBaseline()) publish(actor,id,c.getVersion());
        return c;
    }
    private UtilityCycle finalizeCycle(Long actor,Long id,Long version,boolean scheduled) {
        var c=editable(actor,id);
        if (c.isFinalized()) throw error("Kỳ này đã chốt. Vui lòng mở lại chi tiết.");
        if (!Objects.equals(c.getVersion(),version)) throw error("Dữ liệu vừa thay đổi. Vui lòng mở lại bản xem trước.");
        if (c.getElectricity()==null || c.getWater()==null) throw error("Cần nhập đủ số điện và nước trước khi chốt.");
        if (c.isBaseline()) {
            if(c.getContract().getStartDate().isAfter(BillingDates.today())) throw error("Chưa đến ngày bắt đầu hợp đồng.");
            c.setReadingDate(c.getContract().getStartDate());
            c.setPeriodStart(c.getReadingDate());
            c.setPeriodEnd(c.getReadingDate());
            c.setElectricityPrice(prices.at(UtilityType.ELECTRICITY,c.getPeriodStart()).map(UtilityRate::getUnitPrice).orElse(null));
            c.setWaterPrice(prices.at(UtilityType.WATER,c.getPeriodStart()).map(UtilityRate::getUnitPrice).orElse(null));
        } else {
            if(scheduled) {
                c.setAdjusted(false); c.setCheckout(false); c.setPeriodEnd(c.getPlannedEnd());
            }
            if (!scheduled && !c.isAdjusted() && c.getReadingDate().isBefore(c.getPlannedEnd())) throw error("Chưa đến ngày chốt. Chỉ số đã lưu nháp; đến hạn hãy cập nhật số thực tế.");
            if (!scheduled && !c.getReadingDate().equals(c.getPlannedEnd()) && !c.isAdjusted())
                throw error("Ngày đọc khác ngày chốt. Hãy chọn chốt theo ngày thực tế và nhập tiền phòng đã thỏa thuận.");
            if (c.getRent()==null) throw error("Kỳ cuối không đủ tháng. Vui lòng nhập tiền phòng đã thỏa thuận.");
            if (c.isAdjusted()) c.setPeriodEnd(c.getReadingDate());
            c.setElectricityPrice(c.getElectricityPrice()!=null?c.getElectricityPrice():price(UtilityType.ELECTRICITY,c.getPeriodStart()));
            c.setWaterPrice(c.getWaterPrice()!=null?c.getWaterPrice():price(UtilityType.WATER,c.getPeriodStart()));
        }
        c.setFinalized(true); c.setFinalizedAt(LocalDateTime.now()); cycles.saveAndFlush(c);
        audit.logAction(actor,"LANDLORD_FINALIZE_UTILITY_CYCLE","Chốt điện nước #"+id+" "+c.periodLabel());
        if (!c.isBaseline()) {
            String message="⚡💧 ĐÃ CHỐT ĐIỆN NƯỚC\nPhòng "+c.getContract().getRoom().getRoomNumber()+"\nKỳ: "+c.periodLabel()
                    +"\nĐiện: "+c.electricityAmount().toPlainString()+" VNĐ\nNước: "+c.waterAmount().toPlainString()+" VNĐ"
                    +"\nTiền điện nước đã được chốt.";
            notifyMembers(c,NotificationType.UTILITY_CYCLE_FINALIZED,"Chốt điện nước",message,"UTILITY_CYCLE",id);
        }
        return c;
    }
    public record Estimate(BigDecimal electricityPrice,BigDecimal waterPrice,BigDecimal electricityAmount,BigDecimal waterAmount) {}
    @Transactional(readOnly=true)
    public Estimate estimate(Long actor,Long id) {
        var c=get(actor,id);
        if(c.isBaseline() || c.getElectricity()==null || c.getWater()==null) throw error("Chưa đủ số điện nước.");
        var ep=c.getElectricityPrice()!=null?c.getElectricityPrice():price(UtilityType.ELECTRICITY,c.getPeriodStart());
        var wp=c.getWaterPrice()!=null?c.getWaterPrice():price(UtilityType.WATER,c.getPeriodStart());
        return new Estimate(ep,wp,c.getElectricity().subtract(c.getPreviousElectricity()).multiply(ep),c.getWater().subtract(c.getPreviousWater()).multiply(wp));
    }
    private void notifyMembers(UtilityCycle c,NotificationType type,String title,String message,String reference,Long id) {
        for(var member:users.findByRoleWithRoom(Role.MEMBER)) {
            boolean currentResident=type==NotificationType.INVOICE_CREATED
                    && c.getContract().getStatus()==ContractStatus.ACTIVE
                    && member.getRoom()!=null && member.getRoom().getId().equals(c.getContract().getRoom().getId());
            if(!currentResident && !canRead(member,c)) continue;
            notifications.createNotification(member.getTelegramId(),type,title,message,reference,id);
            events.publishEvent(new com.boardinghousebot.observer.UtilityCycleMessage(member.getTelegramId(),message));
        }
    }
    @Transactional(readOnly=true)
    public Optional<Long> invoiceId(Long id) { return invoices.findByUtilityCycleId(id).map(Invoice::getId); }
    private BigDecimal price(UtilityType type,LocalDate start) {
        return prices.at(type,start).orElseThrow(() -> error("Chưa có giá "+type.getDisplayName()+" tại đầu kỳ. Cần kiểm tra lịch sử đơn giá, không tự dùng giá hiện tại để tính ngược.")).getUnitPrice();
    }
    public Invoice preview(Long actor,Long id) {
        landlord(actor); var c=get(actor,id);
        if (c.isBaseline() || !c.isFinalized()) throw error("Cần chốt đủ điện nước của kỳ trước khi lập hóa đơn.");
        if (invoices.existsByUtilityCycleId(id)) throw error("Kỳ này đã có hóa đơn.");
        Contract contract=c.getContract();
        var old=invoices.findByRoomIdAndBillingMonthAndBillingYear(contract.getRoom().getId(),c.getPlannedEnd().getMonthValue(),c.getPlannedEnd().getYear());
        if(old.filter(i -> i.getContract()!=null && i.getContract().getId().equals(contract.getId())).isPresent())
            throw error("Đã có hóa đơn tháng cũ cho hợp đồng và kỳ thanh toán này. Vui lòng kiểm tra mốc chuyển đổi để tránh tính trùng.");
        var total=c.getRent().add(c.electricityAmount()).add(c.waterAmount());
        var result=new Invoice(contract.getRoom(),contract.getMember(),contract,c.getPeriodEnd().getMonthValue(),c.getPeriodEnd().getYear(),
                c.getRent(),c.electricityAmount(),c.waterAmount(),total,c.getPlannedEnd());
        result.setUtilityCycleId(id); result.setPeriodStart(c.getPeriodStart()); result.setPeriodEnd(c.getPeriodEnd());
        if(c.isCheckout()) {
            result.setNote("Người thuê trả phòng");
            result.setCheckoutDate(c.getPeriodEnd());
        }
        return result;
    }
    public Invoice publish(Long actor,Long id,Long version) {
        landlord(actor); var c=get(actor,id);
        if (!Objects.equals(c.getVersion(),version)) throw error("Chỉ số vừa thay đổi. Vui lòng xem lại hóa đơn nháp.");
        var invoice=preview(actor,id); invoice.setStatus(invoice.calculateCurrentStatus());
        var saved=invoices.saveAndFlush(invoice);
        audit.logAction(actor,"LANDLORD_CREATE_INVOICE","Phát hành hóa đơn #"+saved.getId()+" kỳ "+c.periodLabel());
        String message="💰 HÓA ĐƠN MỚI\nPhòng: "+saved.getRoom().getRoomNumber()+"\nKỳ sử dụng: "+saved.getPeriodDisplay()
                +"\nTiền phòng: "+saved.getRoomRentAmount().toPlainString()+" VNĐ\nĐiện: "+saved.getElectricityAmount().toPlainString()
                +" VNĐ\nNước: "+saved.getWaterAmount().toPlainString()+" VNĐ\nTổng: "+saved.getTotalAmount().toPlainString()
                +" VNĐ\nHạn thanh toán: "+saved.getDueDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        if(saved.getCheckoutDate()!=null) message+="\nGhi chú: "+saved.getNote()
                +"\nNgày trả phòng: "+saved.getCheckoutDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        notifyMembers(c,NotificationType.INVOICE_CREATED,"Hóa đơn mới",message,"INVOICE",saved.getId());
        if(saved.getCheckoutDate()!=null) checkoutSettlement.settle(actor,saved.getContract().getId());
        return saved;
    }
}
