/*!
* Copyright 2002 - 2013 Webdetails, a Pentaho company.  All rights reserved.
* 
* This software was developed by Webdetails and is provided under the terms
* of the Mozilla Public License, Version 2.0, or any later version. You may not use
* this file except in compliance with the license. If you need a copy of the license,
* please go to  http://mozilla.org/MPL/2.0/. The Initial Developer is Webdetails.
*
* Software distributed under the Mozilla Public License is distributed on an "AS IS"
* basis, WITHOUT WARRANTY OF ANY KIND, either express or  implied. Please refer to
* the license for the specific language governing your rights and limitations.
*/

package pt.webdetails.cda.cache;

import java.util.Date;
import java.util.Map;
import java.util.PriorityQueue;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.pentaho.platform.api.engine.IAcceptsRuntimeInputs;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.IUserDetailsRoleListService;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.core.system.StandaloneSession;
import org.pentaho.platform.engine.core.system.UserSession;
import org.pentaho.platform.engine.security.SecurityHelper;
import org.pentaho.platform.scheduler.QuartzSystemListener;
import org.pentaho.platform.scheduler.SchedulerHelper;
import org.quartz.Scheduler;
import org.springframework.security.Authentication;
import org.springframework.security.GrantedAuthority;
import org.springframework.security.providers.UsernamePasswordAuthenticationToken;
import pt.webdetails.cda.CdaBoot;
import pt.webdetails.cda.utils.PluginHibernateUtil;

/**
 *
 * @author pdpi
 */
public class CacheActivator implements IAcceptsRuntimeInputs
{

  static final String TRIGGER_NAME = "cacheWarmer";
  static final String BACKUP_TRIGGER_NAME = "backupCacheWarmer";
  static final String JOB_GROUP = "CDA";
  static final String JOB_ACTION = "scheduler.xaction";
  static final String BACKUP_JOB_ACTION = "backupScheduler.xaction";
  static final long ONE_HOUR = 3600000; // In miliseconds


  public CacheActivator()
  {
  }


  public void setInputs(Map<String, Object> map)
  {
    return;
  }


  public boolean execute() throws Exception
  {
    ClassLoader contextCL = Thread.currentThread().getContextClassLoader();
    Session session = PluginHibernateUtil.getSession();
    Date rightNow = new Date();
    
    Transaction transaction = null;
    try
    {
      Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());

      transaction = session.beginTransaction();
      /* If there's any work at all to be done, the first thing we do is proactively
       * reschedule this action to one hour from now, to ensure that, if for some
       * reason the queue fails to reschedule after excuting all due queries, we'll
       * still recover at some point in the future.
       */
      PriorityQueue<CachedQuery> queue = CacheScheduleManager.getInstance().getQueue();
      if (queue.peek().getNextExecution().before(rightNow))
      {
        Date anHourFromNow = new Date(rightNow.getTime() + ONE_HOUR);
        reschedule(anHourFromNow);
      }
      else
      {
        CacheScheduleManager.logger.info("No work to be done");
      }
      while (queue.peek().getNextExecution().before(rightNow))
      {
        processQueries(session, queue);
        rightNow = new Date();
      }
      reschedule(queue);
      session.flush();
      session.getTransaction().commit();
      return true;
    }
    catch (Exception e)
    {
      if (transaction != null) {
        transaction.rollback();
      }
      return false;
    }
    finally
    {
      session.close();
      Thread.currentThread().setContextClassLoader(contextCL);
    }
  }


  public boolean validate() throws Exception
  {
    return true;
  }


  public void processQueries(Session s, PriorityQueue<CachedQuery> queue)
  {

    CacheScheduleManager.logger.debug("Refreshing cached query...");
    CachedQuery q = queue.poll();
    try
    {
      IPentahoSession session = PentahoSessionHolder.getSession();
      s.refresh(q);
      setSession(q);
      q.execute();
      PentahoSessionHolder.setSession(session);
    }
    catch (Exception ex)
    {
      CacheScheduleManager.logger.error("Failed to execute " + q.toString());
    }


    q.updateNext();
    queue.add(q);
    s.update(q);
  }


  public static void reschedule(PriorityQueue<CachedQuery> queue)
  {
    CachedQuery q = queue.peek();

    Date dueAt = ( q != null)? q.getNextExecution() : null;
    IPentahoSession session = new StandaloneSession(JOB_GROUP);
    Scheduler sched = QuartzSystemListener.getSchedulerInstance();

    SchedulerHelper.deleteJob(session, JOB_ACTION, JOB_GROUP);
    SchedulerHelper.createSimpleTriggerJob(session, "system", "cda/actions", JOB_ACTION, TRIGGER_NAME, JOB_GROUP, "", dueAt, null, 0, 0);

  }


  public static void reschedule(Date date)
  {

    IPentahoSession session = new StandaloneSession(JOB_GROUP);
    SchedulerHelper.deleteJob(session, JOB_ACTION, JOB_GROUP);
    SchedulerHelper.createSimpleTriggerJob(session, "system", "cda/actions", JOB_ACTION, TRIGGER_NAME, JOB_GROUP, "", date, null, 0, 0);


  }


  public static void setSession(CachedQuery q)
  {
    IUserDetailsRoleListService userDetailsRoleListService = PentahoSystem.getUserDetailsRoleListService();
    String user = q.getUserName();
    UserSession session = new UserSession(user, null, false, null);
    GrantedAuthority[] auths = userDetailsRoleListService.getUserRoleListService().getAuthoritiesForUser(user);
    Authentication auth = new UsernamePasswordAuthenticationToken(user, null, auths);
    session.setAttribute(SecurityHelper.SESSION_PRINCIPAL, auth);
    session.doStartupActions(null);
    PentahoSessionHolder.setSession(session);
  }


  public static void rescheduleBackup()
  {
    String cron = CdaBoot.getInstance().getGlobalConfig().getConfigProperty("pt.webdetails.cda.cache.backupWarmerCron");
    cron = cron == null ? "0 0 0/30 * * ?" : cron;
    IPentahoSession session = new StandaloneSession(JOB_GROUP);
    SchedulerHelper.deleteJob(session, BACKUP_JOB_ACTION, JOB_GROUP);
    SchedulerHelper.createCronJob(session, "system", "cda/actions", BACKUP_JOB_ACTION, BACKUP_TRIGGER_NAME, JOB_GROUP, "", cron);
  }
}
