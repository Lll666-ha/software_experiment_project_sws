package com.sws.studyroom;

import com.sws.studyroom.accountrooms.AccountRoomServlet;
import com.sws.studyroom.bookingrules.BookingRuleServlet;
import com.sws.studyroom.usagefeedback.*;
import com.sws.studyroom.common.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.sws.studyroom.common.Sql.*;

/** Standalone integration checks, against an isolated MySQL database. No mock JDBC. */
public final class IntegrationChecks {
    private static int passed;
    private static final Map<String,Object> contextValues=new ConcurrentHashMap<>();
    private static final ServletContext context=proxy(ServletContext.class,(m,a)->switch(m){
        case "getAttribute"->contextValues.get(a[0]);
        case "setAttribute"->{contextValues.put((String)a[0],a[1]);yield null;}
        case "log"->{System.err.println(Arrays.toString(a));yield null;}
        default->null;
    });
    private static final AccountRoomServlet account=new AccountRoomServlet();
    private static final BookingRuleServlet booking=new BookingRuleServlet();
    private static final UsageFeedbackServlet usage=new UsageFeedbackServlet();
    private static final ActiveAccountFilter filter=new ActiveAccountFilter();
    private static final LocalDateTime NOW=LocalDateTime.of(2026,11,27,14,0);
    private static final UsageService fixed=new UsageService(booking,Clock.fixed(NOW.atZone(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneId.of("Asia/Shanghai")));
    @FunctionalInterface private interface Call { Object apply(String m,Object[] a)throws Throwable; }
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,Call call){
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->{
            if(m.getName().equals("toString"))return type.getSimpleName();
            Object v=call.apply(m.getName(),a==null?new Object[0]:a);
            if(v!=null||!m.getReturnType().isPrimitive()||m.getReturnType()==void.class)return v;
            if(m.getReturnType()==boolean.class)return false;if(m.getReturnType()==long.class)return 0L;return 0;
        });
    }
    private static void ok(boolean b,String name){if(!b)throw new AssertionError(name);System.out.println("PASS "+(++passed)+" "+name);}
    private static final class Client {
        Map<String,Object> attributes=new HashMap<>();boolean valid;String token;
        final HttpSession session=proxy(HttpSession.class,(m,a)->switch(m){
            case "getAttribute"->attributes.get(a[0]);
            case "setAttribute"->{attributes.put((String)a[0],a[1]);yield null;}
            case "removeAttribute"->{attributes.remove(a[0]);yield null;}
            case "invalidate"->{attributes.clear();valid=false;yield null;}
            default->null;
        });
        Response request(HttpServlet servlet,String method,String path,Map<String,String> params)throws Exception{
            var reqAttrs=new HashMap<String,Object>();var fields=new HashMap<>(params);
            if(method.equals("POST")&&token!=null)fields.putIfAbsent("csrf",token);
            HttpServletRequest req=proxy(HttpServletRequest.class,(m,a)->switch(m){
                case "getMethod"->method;case "getPathInfo"->path;case "getProtocol"->"HTTP/1.1";
                case "getParameter"->fields.get(a[0]);
                case "getParameterMap"->{Map<String,String[]> map=new HashMap<>();fields.forEach((k,v)->map.put(k,new String[]{v}));yield map;}
                case "getSession"->{if(a.length==0||Boolean.TRUE.equals(a[0]))valid=true;yield valid?session:null;}
                case "changeSessionId"->UUID.randomUUID().toString();
                case "getServletContext"->context;
                case "getAttribute"->reqAttrs.get(a[0]);case "setAttribute"->{reqAttrs.put((String)a[0],a[1]);yield null;}
                case "getContentType"->"application/x-www-form-urlencoded";
                default->null;
            });
            var result=new Response();filter.doFilter(req,result.response,(r,s)->servlet.service(r,s));
            if(path.equals("/token")&&result.status==200)token=Json.parse(result.body.toString()).get("csrf");
            return result;
        }
    }
    private static final class Response {
        int status=200;StringWriter body=new StringWriter();
        HttpServletResponse response=proxy(HttpServletResponse.class,(m,a)->switch(m){
            case "getWriter"->new PrintWriter(body);case "setStatus","sendError"->{status=(int)a[0];yield null;}default->null;
        });
        boolean is(int expected){if(status!=expected)System.err.println("HTTP "+status+" "+body);return status==expected;}
    }
    private static Response register(Client client, String name, String contact) throws Exception {
        var code = client.request(account,"POST","/register/code",Map.of("contact",contact));
        ok(code.is(200), "issue contact verification code");
        return client.request(account,"POST","/register",Map.of("username",name,"contact",contact,
                "password","Test@123456","password_confirm","Test@123456","code",Json.parse(code.body.toString()).get("demo_code")));
    }
    private static long user(String username)throws SQLException{
        try(var c=Database.open()){return Long.parseLong(one(c,"SELECT user_id FROM `user` WHERE username=?",username).get("user_id").toString());}
    }
    private static void sql(String statement,Object...args)throws SQLException{try(var c=Database.open()){update(c,statement,args);}}
    private static Map<String,Object> row(String statement,Object...args)throws SQLException{try(var c=Database.open()){return one(c,statement,args);}}
    @FunctionalInterface private interface Tx<T>{T run(Connection c)throws Exception;}
    private static <T>T tx(Tx<T> action)throws Exception{try(var c=Database.open()){c.setAutoCommit(false);try{T result=action.run(c);c.commit();return result;}catch(Exception ex){c.rollback();throw ex;}}}
    private static long fixture(long user,LocalDateTime start,LocalDateTime end,String status)throws Exception{
        return tx(c->{long id=insert(c,"INSERT INTO booking(user_id,room_id,start_time,end_time,total_amount,booking_status,rule_version_id,pay_deadline) VALUES (?,9101,?,?,10,?,1,?)",user,start,end,status,start);
            update(c,"INSERT INTO slot_lock(booking_id,user_id,room_id,slot_start,slot_end) VALUES (?,?,9101,?,?)",id,user,start,end);
            if(status.equals("PENDING_CHECKIN"))update(c,"INSERT INTO payment(booking_id,pay_amount,paid_at) VALUES (?,10,?)",id,NOW.minusHours(1));return id;});
    }
    private static void failure(int status,Tx<?> action,String label)throws Exception{
        try{tx(action);throw new AssertionError(label+" unexpectedly succeeded");}catch(UsageService.Failure ex){ok(ex.status==status,label);}
    }
    public static void main(String[]args)throws Exception{
        if(!"1".equals(System.getenv("SWS_INTEGRATION_TEST")))throw new IllegalStateException("Only run against an isolated test database");
        ServletConfig config=proxy(ServletConfig.class,(m,a)->m.equals("getServletContext")?context:null);
        account.init(config);booking.init(config);usage.init(config);
        ok(Json.parse("{ \"x\" : \"a\\\"b\\n中\", \"n\" : 4 }").get("x").equals("a\"b\n中"),"JSON whitespace, escaped quote, newline, Unicode");
        for(String bad:List.of("{\"x\":}","{\"x\":1,\"x\":2}","{\"x\":1}junk","{\"x\":\"bad\\q\"}")){
            try{Json.parse(bad);throw new AssertionError("invalid JSON accepted");}catch(IllegalArgumentException expected){ok(true,"reject malformed JSON");}
        }
        Client a=new Client(),b=new Client(),admin=new Client();
        a.request(account,"GET","/token",Map.of());b.request(account,"GET","/token",Map.of());admin.request(account,"GET","/token",Map.of());
        String suffix=Long.toString(System.nanoTime()).replace("-","");String name="ci_"+suffix;
        String phone="139"+suffix.substring(suffix.length()-8);
        String email=name+"@example.test";
        ok(register(a,name,phone).is(200),"registration signs in after commit");
        long owner=user(name);
        ok(((Number)row("SELECT credit_score FROM `user` WHERE user_id=?",owner).get("credit_score")).intValue()==300,"initial credit 300");
        ok(register(b,name+"b",email).is(200),"email registration");long other=user(name+"b");
        Client locked=new Client();
        locked.request(account,"GET","/token",Map.of());
        for(int attempt=1;attempt<=5;attempt++){
            var failure=locked.request(account,"POST","/login",Map.of("username",email,"password","Wrong123456"));
            ok(failure.is(attempt==5?423:401),"persistent login failure "+attempt);
        }
        ok(locked.request(account,"POST","/login",Map.of("username",email,"password","Test@123456")).is(423),"locked account rejects correct password");
        var reset=locked.request(account,"POST","/password-reset/request",Map.of("username",name+"b","contact",email));
        ok(reset.is(200),"email password recovery code");
        ok(locked.request(account,"POST","/password-reset/confirm",Map.of("username",name+"b","contact",email,
                "new_password","Reset@123456","password_confirm","Reset@123456","code",Json.parse(reset.body.toString()).get("demo_code"))).is(200),"reset clears login lock");
        ok(locked.request(account,"POST","/login",Map.of("username",email,"password","Reset@123456")).is(200),"login by verified email");
        ok(admin.request(account,"POST","/login",Map.of("username","room_admin","password","Admin@123456")).is(200),"seeded administrator login");
        ok(a.request(booking,"POST","/orders",Map.of("csrf","invalid")).is(403),"CSRF rejected");
        var tomorrow=LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDate().plusDays(1).atTime(10,0);
        var orderFields=Map.of("room_id","9101","start_time",tomorrow.toString(),"end_time",tomorrow.plusHours(1).toString());
        ok(a.request(booking,"POST","/orders",orderFields).is(200),"ACTIVE user can reserve");
        long order=Long.parseLong(row("SELECT MAX(booking_id) id FROM booking WHERE user_id=?",owner).get("id").toString());
        ok(b.request(booking,"POST","/orders",orderFields).is(409),"room conflict rejected");
        ok(b.request(booking,"POST","/pay",Map.of("booking_id",""+order)).is(400),"cannot pay another user's order");
        ok(a.request(booking,"POST","/pay",Map.of("booking_id",""+order)).is(200),"simulated payment");
        ok(a.request(booking,"POST","/refund",Map.of("booking_id",""+order)).is(200),"simulated refund");
        ok(row("SELECT occupancy_id FROM slot_lock WHERE booking_id=?",order)==null,"refund releases occupancy");
        ok(a.request(booking,"POST","/orders",orderFields).is(200),"reuse released slot");
        long failedPay=Long.parseLong(row("SELECT MAX(booking_id) id FROM booking WHERE user_id=?",owner).get("id").toString());
        ok(a.request(booking,"POST","/pay",Map.of("booking_id",""+failedPay,"simulation_outcome","FAILED")).is(200),"simulated payment failure");
        ok(row("SELECT occupancy_id FROM slot_lock WHERE booking_id=?",failedPay)==null,"failed payment releases slot");
        ok(row("SELECT release_reason FROM booking_outcome WHERE booking_id=?",failedPay).get("release_reason").equals("PAYMENT_FAILED"),"payment failure reason persisted");
        var late=Map.of("booking_id",""+failedPay,"simulation_outcome","LATE_SUCCESS");
        ok(a.request(booking,"POST","/pay",late).is(200),"late payment compensation");
        ok(a.request(booking,"POST","/pay",late).is(200),"repeated late payment does not duplicate refund");
        ok(row("SELECT booking_status FROM booking WHERE booking_id=?",failedPay).get("booking_status").equals("CANCELLED"),"late payment never reopens cancelled booking");
        ok(a.request(booking,"POST","/orders",orderFields).is(200),"reuse failed payment slot");
        long pendingRefund=Long.parseLong(row("SELECT MAX(booking_id) id FROM booking WHERE user_id=?",owner).get("id").toString());
        var pendingFields=Map.of("booking_id",""+pendingRefund);
        ok(a.request(booking,"POST","/pay",pendingFields).is(200),"pay refundable order");
        ok(a.request(booking,"POST","/refund",Map.of("booking_id",""+pendingRefund,"simulation_outcome","FAILED")).is(200),"failed refund enters manual queue");
        ok(row("SELECT occupancy_id FROM slot_lock WHERE booking_id=?",pendingRefund)!=null,"failed refund retains slot");
        ok(admin.request(booking,"POST","/admin/resolve-refund",pendingFields).is(403),"room administrator cannot settle refunds");
        Client superAdmin=new Client();
        superAdmin.request(account,"GET","/token",Map.of());
        sql("UPDATE `user` SET role='SUPER_ADMIN' WHERE user_id=?",other);
        ok(superAdmin.request(account,"POST","/login",Map.of("username",email,"password","Reset@123456")).is(200),"super administrator login");
        ok(superAdmin.request(booking,"GET","/admin/refunds",Map.of()).body.toString().contains("SIM-REFUND-"+pendingRefund),"refund appears in supervisor queue");
        ok(superAdmin.request(booking,"POST","/admin/resolve-refund",pendingFields).is(200),"manual refund succeeds");
        ok(superAdmin.request(booking,"POST","/admin/resolve-refund",pendingFields).is(200),"manual refund retry is idempotent");
        ok(row("SELECT occupancy_id FROM slot_lock WHERE booking_id=?",pendingRefund)==null,"manual refund releases slot");
        sql("UPDATE `user` SET role='USER' WHERE user_id=?",other);
        ok(a.request(booking,"POST","/orders",orderFields).is(200),"reserve again after verified refund");
        long asynchronous=Long.parseLong(row("SELECT MAX(booking_id) id FROM booking WHERE user_id=?",owner).get("id").toString());
        ok(a.request(booking,"POST","/pay",Map.of("booking_id",""+asynchronous)).is(200),"pay asynchronous-refund order");
        ok(a.request(booking,"POST","/refund",Map.of("booking_id",""+asynchronous,"simulation_outcome","PROCESSING")).is(200),"refund waits for simulated callback");
        var simulatedNow=LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusMinutes(2);
        tx(c->{booking.completeDueRefund(c,asynchronous,simulatedNow);booking.completeDueRefund(c,asynchronous,simulatedNow);return null;});
        ok(row("SELECT booking_id FROM refund WHERE booking_id=?",asynchronous)!=null,"simulated callback refunds exactly once");
        ok(row("SELECT occupancy_id FROM slot_lock WHERE booking_id=?",asynchronous)==null,"simulated callback releases the slot");
        var simultaneous=Map.of("room_id","9102","start_time",tomorrow.plusHours(3).toString(),"end_time",tomorrow.plusHours(4).toString());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->a.request(booking,"POST","/orders",simultaneous));
            var second=pool.submit(()->b.request(booking,"POST","/orders",simultaneous));
            int left=first.get().status,right=second.get().status;
            ok((left==200&&right==409)||(left==409&&right==200),"concurrent room reservation has exactly one winner");
            var winning=row("SELECT booking_id,user_id FROM slot_lock WHERE room_id=9102 AND slot_start=?",tomorrow.plusHours(3));
            Client winner=Long.parseLong(winning.get("user_id").toString())==owner?a:b;
            ok(winner.request(booking,"POST","/cancel",Map.of("booking_id",winning.get("booking_id").toString())).is(200),"cancel concurrent winner releases lock");
        } finally { pool.shutdownNow(); }
        long leftEdge=fixture(owner,NOW.plusMinutes(15),NOW.plusMinutes(75),"PENDING_CHECKIN");
        tx(c->fixed.checkIn(c,leftEdge,owner));tx(c->fixed.finish(c,leftEdge,owner));
        ok(true,"checkin includes start minus 15 minutes");
        long rightEdge=fixture(owner,NOW.minusMinutes(15),NOW.plusMinutes(45),"PENDING_CHECKIN");
        tx(c->{fixed.expire(c,rightEdge);return null;});
        ok("PENDING_CHECKIN".equals(row("SELECT booking_status FROM booking WHERE booking_id=?",rightEdge).get("booking_status")),"timeout does not fire at the inclusive right boundary");
        tx(c->fixed.checkIn(c,rightEdge,owner));tx(c->fixed.finish(c,rightEdge,owner));
        ok(true,"checkin includes start plus 15 minutes");
        long tooEarly=fixture(owner,NOW.plusMinutes(16),NOW.plusMinutes(76),"PENDING_CHECKIN");
        failure(409,c->fixed.checkIn(c,tooEarly,owner),"checkin rejects before left boundary");
        long live=fixture(owner,NOW,NOW.plusHours(1),"PENDING_CHECKIN");
        failure(403,c->fixed.checkIn(c,live,other),"reject foreign checkin");
        ok(tx(c->fixed.checkIn(c,live,owner,true)).get("door_open_result").equals("FAILED"),"simulated gate failure persists");
        ok(tx(c->fixed.checkIn(c,live,owner)).get("door_open_result").equals("FAILED"),"repeated check-in keeps gate result");
        ok(((Number)row("SELECT COUNT(*) n FROM `usage` WHERE booking_id=?",live).get("n")).intValue()==1,"checkin is idempotent");
        failure(403,c->fixed.finish(c,live,other),"reject foreign finish");
        ok(tx(c->fixed.retryDoor(c,live,owner)).get("door_open_result").equals("SUCCESS"),"failed door succeeds on retry");
        tx(c->fixed.retryDoor(c,live,owner));
        ok(((Number)row("SELECT door_attempt_count FROM `usage` WHERE booking_id=?",live).get("door_attempt_count")).intValue()==2,"failed gate can retry");
        tx(c->fixed.finish(c,live,owner));tx(c->fixed.finish(c,live,owner));
        ok("FINISHED".equals(row("SELECT booking_status FROM booking WHERE booking_id=?",live).get("booking_status"))&&row("SELECT occupancy_id FROM slot_lock WHERE booking_id=?",live)==null,"finish changes order and releases slots atomically");
        failure(403,c->fixed.review(c,live,other,5,5,"foreign"),"reject foreign review");
        tx(c->fixed.review(c,live,owner,5,4,"含\"引号\"、换行\n和中文"));tx(c->fixed.review(c,live,owner,5,4,"again"));
        ok(((Number)row("SELECT COUNT(*) n FROM review WHERE booking_id=?",live).get("n")).intValue()==1,"one review per booking");
        sql("UPDATE `usage` SET actual_end_time=? WHERE booking_id=?",NOW.minusHours(24),leftEdge);
        tx(c->fixed.review(c,leftEdge,owner,5,5,"边界"));ok(true,"review includes 24-hour deadline");
        sql("UPDATE `usage` SET actual_end_time=? WHERE booking_id=?",NOW.minusHours(24).minusSeconds(1),rightEdge);
        failure(409,c->fixed.review(c,rightEdge,owner,5,5,"过期"),"review rejects after 24-hour deadline");
        long noShow=fixture(owner,NOW.minusHours(2),NOW.minusHours(1),"PENDING_CHECKIN");
        tx(c->{fixed.expire(c,noShow);return null;});tx(c->{fixed.expire(c,noShow);return null;});
        ok(tx(c->fixed.findScore(c,owner))==290,"timeout deducts once");
        long violation=Long.parseLong(row("SELECT violation_id FROM violation WHERE booking_id=?",noShow).get("violation_id").toString());
        var appeal=tx(c->fixed.appeal(c,noShow,violation,owner,"请求复核"));long appealId=Long.parseLong(appeal.get("appeal_id").toString());
        ok(tx(c->fixed.appeal(c,noShow,violation,owner,"重试")).get("appeal_id").equals(appeal.get("appeal_id")),"pending appeal is idempotent");
        var roomAdmin=new SessionIdentity(user("room_admin"),SessionIdentity.Role.ROOM_ADMIN);
        failure(403,c->fixed.processAppeal(c,appealId,new SessionIdentity(other,SessionIdentity.Role.USER),"APPROVED","同意"),"ordinary user cannot review appeal");
        tx(c->fixed.processAppeal(c,appealId,roomAdmin,"APPROVED","复核通过"));tx(c->fixed.processAppeal(c,appealId,roomAdmin,"APPROVED","重试"));
        ok(tx(c->fixed.findScore(c,owner))==300,"approved appeal restores actual deduction once");
        ok(row("SELECT refund_amount FROM refund WHERE booking_id=?",noShow)!=null,"approved appeal refunds simulated payment");
        tx(c->fixed.applyChange(c,owner,"manual:test",-300,"test"));tx(c->fixed.applyChange(c,owner,"manual:test",-300,"test"));
        ok(tx(c->fixed.findScore(c,owner))==0,"credit lower bound and idempotency");
        failure(409,c->fixed.applyChange(c,other,"manual:test",-10,"test"),"event cannot be replayed for another user");
        tx(c->fixed.applyChange(c,owner,"manual:restore",300,"test"));
        ok(row("SELECT credit_suspended_until FROM `user` WHERE user_id=?",owner).get("credit_suspended_until")!=null,"credit restore preserves suspension");
        var fault=tx(c->fixed.fault(c,9101,owner,"设备故障"));long faultId=Long.parseLong(fault.get("fault_id").toString());
        failure(409,c->fixed.processFault(c,faultId,roomAdmin,"RESOLVED","完成"),"cannot skip repair state");
        tx(c->fixed.processFault(c,faultId,roomAdmin,"REPAIRING","维修中"));sql("UPDATE room SET listing_status='UNLISTED' WHERE room_id=9101");
        tx(c->fixed.processFault(c,faultId,roomAdmin,"RESOLVED","维修完成"));
        ok("UNLISTED".equals(row("SELECT listing_status FROM room WHERE room_id=9101").get("listing_status")),"repair never relists unlisted room");
        sql("UPDATE `user` SET account_status='DISABLED' WHERE user_id=?",owner);
        ok(a.request(usage,"GET","/me/credit",Map.of()).is(403),"freeze blocks an existing session");
        System.out.println("All "+passed+" integration checks passed.");
    }
}
