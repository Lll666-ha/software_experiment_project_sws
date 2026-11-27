package com.sws.studyroom.usagefeedback;
import com.sws.studyroom.common.*;
import com.sws.studyroom.common.ModuleContracts.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.sws.studyroom.common.Sql.*;

@WebServlet(value="/api/usage-feedback/*", loadOnStartup=2)
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
                c.setAutoCommit(false);
                try{Object data=write?change(c,actor,path,input):read(c,actor,path,input);c.commit();send(res,200,Map.of("message","操作成功","data",data));}
                catch(SQLException|RuntimeException ex){c.rollback();throw ex;}
            }
        }catch(UsageService.Failure ex){send(res,ex.status,Map.of("message",ex.getMessage()));}
        catch(IllegalArgumentException ex){send(res,400,Map.of("message",ex.getMessage()==null?"参数格式错误":ex.getMessage()));}
        catch(SQLException ex){log("使用与反馈数据库操作失败",ex);send(res,ex.getErrorCode()==1062?409:500,Map.of("message",ex.getErrorCode()==1062?"操作已提交或事件编号重复":"数据库操作失败"));}
    }
    private Object change(Connection c,SessionIdentity a,String path,Map<String,String> p)throws SQLException{
        long user=a.userId();return switch(path){
            case "/checkin"->service.checkIn(c,number(p,"booking_id"),user);
            case "/door/retry"->service.retryDoor(c,number(p,"booking_id"),user);
            case "/finish"->service.finish(c,number(p,"booking_id"),user);
            case "/review"->service.review(c,number(p,"booking_id"),user,Integer.parseInt(required(p,"score_1")),Integer.parseInt(required(p,"score_2")),Optional.ofNullable(p.get("comment")).orElse(""));
            case "/fault"->service.fault(c,number(p,"room_id"),user,required(p,"fault_desc"));
            case "/appeal"->service.appeal(c,number(p,"booking_id"),number(p,"violation_id"),user,required(p,"appeal_reason"));
            case "/admin/appeal"->service.processAppeal(c,number(p,"appeal_id"),a,required(p,"decision"),required(p,"admin_opinion"));
            case "/admin/fault"->service.processFault(c,number(p,"fault_id"),a,required(p,"fault_status"),required(p,"admin_opinion"));
            default->throw new UsageService.Failure(404,"接口不存在");
        };
    }
    private Object read(Connection c,SessionIdentity a,String path,Map<String,String> p)throws SQLException{
        return switch(path){
            case "/me/credit"->service.credit(c,a.userId());
            case "/me/appeals"->rows(c,"SELECT * FROM appeal WHERE user_id=? ORDER BY appeal_id DESC LIMIT 100",a.userId());
            case "/me/faults"->rows(c,"SELECT * FROM fault WHERE user_id=? ORDER BY fault_id DESC LIMIT 100",a.userId());
            case "/admin/faults"->service.adminList(c,a,"faults");
            case "/admin/appeals"->service.adminList(c,a,"appeals");
            case "/reviews"->{int page=Integer.parseInt(p.getOrDefault("page","1"));if(page<1||page>10000)throw new IllegalArgumentException("页码需为1至10000");
                yield rows(c,"SELECT v.score_1,v.score_2,v.comment,v.created_at FROM review v JOIN booking b USING(booking_id) WHERE b.room_id=? ORDER BY v.created_at DESC,v.review_id DESC LIMIT 20 OFFSET ?",number(p,"room_id"),(page-1)*20);}
            default->throw new UsageService.Failure(404,"接口不存在");
        };
    }
    private static String required(Map<String,String> p,String k){String v=p.get(k);if(v==null||v.isBlank())throw new IllegalArgumentException("请填写 "+k);return v.trim();}
    private static long number(Map<String,String> p,String k){long v=Long.parseLong(required(p,k));if(v<1)throw new IllegalArgumentException("编号需为正整数");return v;}
    private static void send(HttpServletResponse r,int status,Object body)throws IOException{r.setStatus(status);r.getWriter().print(Json.encode(body));}
}
