package com.sws.studyroom.usagefeedback;

import com.sws.studyroom.common.Database;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebListener;
import java.util.concurrent.*;
import static com.sws.studyroom.common.Sql.*;

/** Incremental scans; each order commits separately and repeating a scan is harmless. */
@WebListener
public class LifecycleScheduler implements ServletContextListener {
    private ScheduledExecutorService worker;
    @Override public void contextInitialized(ServletContextEvent event) {
        worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"studyroom-lifecycle");t.setDaemon(true);return t;});
        worker.scheduleWithFixedDelay(()->scan(event.getServletContext()),10,30,TimeUnit.SECONDS);
    }
    private void scan(ServletContext context){
        var service=(UsageService)context.getAttribute(UsageService.class.getName());if(service==null)return;
        try(var c=Database.open()){
            var ids=rows(c,"SELECT b.booking_id FROM booking b JOIN rule_version r USING(rule_version_id) WHERE (booking_status='PENDING_PAYMENT' AND pay_deadline<=?) OR (booking_status='PENDING_CHECKIN' AND TIMESTAMPADD(MINUTE,r.checkin_late_minutes,b.start_time)<?) OR (booking_status='IN_USE' AND end_time<=?) ORDER BY b.booking_id LIMIT 100",service.now(),service.now(),service.now());
            for(var row:ids){
                try(var tx=Database.open()){
                    tx.setAutoCommit(false);
                    try{service.expire(tx,Long.parseLong(row.get("booking_id").toString()));tx.commit();}
                    catch(Exception ex){tx.rollback();context.log("订单自动处理失败",ex);}
                }
            }
        }catch(Exception ex){context.log("订单扫描失败，请检查数据库与迁移脚本",ex);}
    }
    @Override public void contextDestroyed(ServletContextEvent event){if(worker!=null)worker.shutdownNow();}
}
