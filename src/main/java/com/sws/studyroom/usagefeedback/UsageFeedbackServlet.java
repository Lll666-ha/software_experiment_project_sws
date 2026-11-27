package com.sws.studyroom.usagefeedback;
import com.sws.studyroom.common.*;
import com.sws.studyroom.common.ModuleContracts.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.sws.studyroom.common.Sql.*;

@WebServlet(value="/api/usage-feedback/*", loadOnStartup=2)
@MultipartConfig(fileSizeThreshold=0,maxFileSize=10L*1024*1024,maxRequestSize=92L*1024*1024)
public class UsageFeedbackServlet extends HttpServlet {
    private UsageService service;
    @Override public void init() {
        var booking=(BookingAccess)getServletContext().getAttribute(BookingAccess.class.getName());
        if(booking==null)throw new IllegalStateException("预约服务未初始化");
        service=new UsageService(booking,Clock.system(ZoneId.of("Asia/Shanghai")));
        getServletContext().setAttribute(CreditAccess.class.getName(),service);
        getServletContext().setAttribute(UsageService.class.getName(),service);
    }
    @Override protected void doGet(HttpServletRequest req,HttpServletResponse res)throws IOException{serve(req,res,false);}
    @Override protected void doPost(HttpServletRequest req,HttpServletResponse res)throws IOException{serve(req,res,true);}
    private void serve(HttpServletRequest req,HttpServletResponse res,boolean write)throws IOException{
        req.setCharacterEncoding("UTF-8");res.setContentType("application/json;charset=UTF-8");res.setHeader("Cache-Control","no-store");
        var actor=SessionIdentity.current(req).orElse(null);
        if(actor==null){send(res,401,Map.of("message","请先登录"));return;}
        String path=Optional.ofNullable(req.getPathInfo()).orElse("/");
        try{
            Map<String,String> input=new HashMap<>();
            boolean multipart=write&&req.getContentType()!=null&&req.getContentType().toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
            List<FeedbackAttachments.Upload> uploads=List.of();
            if(multipart){
                if(!path.equals("/review")&&!path.equals("/appeal"))throw new IllegalArgumentException("该操作不支持上传附件");
                if(!SessionIdentity.validCsrf(req,req.getHeader("X-CSRF-Token")))
                    throw new UsageService.Failure(403,"页面已失效，请刷新后重试");
                uploads=FeedbackAttachments.validate(req.getParts(),path.equals("/appeal"));
            }
            if(write&&req.getContentType()!=null&&req.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")){
                char[] buffer=new char[1024];var body=new StringBuilder();int n;var reader=req.getReader();
                while((n=reader.read(buffer))!=-1){body.append(buffer,0,n);if(body.length()>8192)throw new UsageService.Failure(413,"请求内容过长");}
                input.putAll(Json.parse(body.toString()));
            }else req.getParameterMap().forEach((k,v)->input.put(k,v[0]));
            if(write){
                String token=req.getHeader("X-CSRF-Token");if(token==null)token=input.get("csrf");
                if(!SessionIdentity.validCsrf(req,token))throw new UsageService.Failure(403,"页面已失效，请刷新后重试");
            }
            if(!write&&path.equals("/token")){send(res,200,Map.of("csrf",SessionIdentity.csrf(req)));return;}
            try(Connection c=Database.open()){
                if(!write&&path.equals("/attachment")){
                    FeedbackAttachments.download(c,actor,number(input,"attachment_id"),res);
                    return;
                }
                c.setAutoCommit(false);
                try{Object data=write?change(c,actor,path,input,uploads):read(c,actor,path,input);c.commit();send(res,200,Map.of("message","操作成功","data",data));}
                catch(SQLException|RuntimeException ex){c.rollback();throw ex;}
            }
        }catch(UsageService.Failure ex){send(res,ex.status,Map.of("message",ex.getMessage()));}
        catch(ServletException|IllegalStateException ex){send(res,400,Map.of("message","上传格式无效或附件超过大小上限"));}
        catch(IllegalArgumentException ex){send(res,400,Map.of("message",ex.getMessage()==null?"参数格式错误":ex.getMessage()));}
        catch(SQLException ex){log("使用与反馈数据库操作失败",ex);send(res,ex.getErrorCode()==1062?409:500,Map.of("message",ex.getErrorCode()==1062?"操作已提交或事件编号重复":"数据库操作失败"));}
    }
    private Object change(Connection c,SessionIdentity a,String path,Map<String,String> p,List<FeedbackAttachments.Upload> uploads)throws SQLException{
        long user=a.userId();return switch(path){
            case "/checkin"->service.checkIn(c,number(p,"booking_id"),user,"FAILED".equals(p.get("simulation_outcome")));
            case "/door/retry"->service.retryDoor(c,number(p,"booking_id"),user);
            case "/finish"->service.finish(c,number(p,"booking_id"),user);
            case "/review"->service.review(c,number(p,"booking_id"),user,Integer.parseInt(required(p,"score_1")),Integer.parseInt(required(p,"score_2")),Optional.ofNullable(p.get("comment")).orElse(""),uploads);
            case "/fault"->service.fault(c,number(p,"room_id"),user,required(p,"fault_desc"));
            case "/appeal"->service.appeal(c,number(p,"booking_id"),number(p,"violation_id"),user,required(p,"appeal_reason"),uploads);
            case "/admin/violation"->service.recordViolation(c,number(p,"booking_id"),a,required(p,"violation_type"),required(p,"evidence"));
            case "/admin/appeal"->service.processAppeal(c,number(p,"appeal_id"),a,required(p,"decision"),required(p,"admin_opinion"));
            case "/admin/fault"->service.processFault(c,number(p,"fault_id"),a,required(p,"fault_status"),required(p,"admin_opinion"));
            case "/messages/read"->{
                Messages.markRead(c,user,number(p,"message_id"),service.now());
                yield Map.of("ok",true);
            }
            default->throw new UsageService.Failure(404,"接口不存在");
        };
    }
    private Object read(Connection c,SessionIdentity a,String path,Map<String,String> p)throws SQLException{
        return switch(path){
            case "/me/credit"->service.credit(c,a.userId());
            case "/messages"->Messages.inbox(c,a.userId(),ListPage.parse(p.get("page"),p.get("page_size")));
            case "/admin/user-credit"->{
                if (a.role() != SessionIdentity.Role.SUPER_ADMIN)
                    throw new UsageService.Failure(403,"仅超级管理员可查询用户信用");
                yield service.credit(c,number(p,"user_id"));
            }
            case "/me/appeals"->withAttachments(c,rows(c,"SELECT * FROM appeal WHERE user_id=? ORDER BY appeal_id DESC LIMIT 100",a.userId()),true);
            case "/me/faults"->rows(c,"SELECT * FROM fault WHERE user_id=? ORDER BY fault_id DESC LIMIT 100",a.userId());
            case "/admin/faults"->service.adminList(c,a,"faults");
            case "/admin/appeals"->withAttachments(c,service.adminList(c,a,"appeals"),true);
            case "/reviews"->{int page=Integer.parseInt(p.getOrDefault("page","1"));if(page<1||page>10000)throw new IllegalArgumentException("页码需为1至10000");
                yield rows(c,"SELECT v.score_1,v.score_2,v.comment,v.created_at FROM review v JOIN booking b USING(booking_id) WHERE b.room_id=? ORDER BY v.created_at DESC,v.review_id DESC LIMIT 20 OFFSET ?",number(p,"room_id"),(page-1)*20);}
            case "/room-review-history"->reviewHistory(c,p);
            default->throw new UsageService.Failure(404,"接口不存在");
        };
    }
    private Object reviewHistory(Connection c, Map<String, String> params) throws SQLException {
        long room = number(params, "room_id");
        if (one(c, "SELECT room_id FROM room WHERE room_id=? AND listing_status='LISTED'", room) == null)
            throw new UsageService.Failure(404, "房间不存在或已下架");
        ListPage page = ListPage.parse(params.get("page"), params.get("page_size"));
        StringBuilder where = new StringBuilder(" FROM review v JOIN booking b USING(booking_id) WHERE b.room_id=?");
        List<Object> args = new ArrayList<>();
        args.add(room);
        for (String score : List.of("score_1", "score_2")) {
            String raw = params.getOrDefault(score, "");
            if (raw.isBlank()) continue;
            int value = Integer.parseInt(raw);
            if (value < 1 || value > 5) throw new IllegalArgumentException("评分必须为 1 到 5");
            where.append(" AND v.").append(score).append("=?");
            args.add(value);
        }
        try {
            String from = params.getOrDefault("from", ""), to = params.getOrDefault("to", "");
            var start = from.isBlank() ? null : java.time.LocalDate.parse(from);
            var end = to.isBlank() ? null : java.time.LocalDate.parse(to);
            if (start != null && end != null && end.isBefore(start))
                throw new IllegalArgumentException("结束日期不能早于开始日期");
            if (start != null) { where.append(" AND v.created_at>=?"); args.add(start.atStartOfDay()); }
            if (end != null) { where.append(" AND v.created_at<?"); args.add(end.plusDays(1).atStartOfDay()); }
        } catch (java.time.DateTimeException ex) {
            throw new IllegalArgumentException("评价日期格式无效", ex);
        }
        Number total = (Number) one(c, "SELECT COUNT(*) AS total" + where, args.toArray()).get("total");
        args.add(page.size()); args.add(page.offset());
        var result = rows(c, "SELECT v.review_id,v.score_1,v.score_2,v.comment,v.created_at" + where
                + " ORDER BY v.created_at DESC,v.review_id DESC LIMIT ? OFFSET ?", args.toArray());
        return page.result(withAttachments(c,result,false), total);
    }
    private List<Map<String,Object>> withAttachments(Connection c,List<Map<String,Object>> items,boolean appeal) throws SQLException {
        for(var record:items)
            record.put("attachments",FeedbackAttachments.list(c,Long.parseLong(record.get(appeal?"appeal_id":"review_id").toString()),appeal));
        return items;
    }
    private static String required(Map<String,String> p,String k){String v=p.get(k);if(v==null||v.isBlank())throw new IllegalArgumentException("请填写 "+k);return v.trim();}
    private static long number(Map<String,String> p,String k){long v=Long.parseLong(required(p,k));if(v<1)throw new IllegalArgumentException("编号需为正整数");return v;}
    private static void send(HttpServletResponse r,int status,Object body)throws IOException{r.setStatus(status);r.getWriter().print(Json.encode(body));}
}
