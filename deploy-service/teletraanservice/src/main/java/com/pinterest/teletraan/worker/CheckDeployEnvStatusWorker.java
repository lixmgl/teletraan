package com.pinterest.teletraan.worker;

import com.pinterest.deployservice.ServiceContext;
import com.pinterest.deployservice.bean.EnvironBean;
import com.pinterest.deployservice.dao.DeployDAO;
import com.pinterest.deployservice.dao.EnvironDAO;
import com.pinterest.deployservice.dao.UtilDAO;
import com.pinterest.deployservice.bean.DeployFilterBean;
import com.pinterest.deployservice.db.DeployQueryFilter;
import com.pinterest.deployservice.bean.DeployQueryResultBean;
import com.pinterest.deployservice.bean.*;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.DataOutputStream;
import java.net.Socket;
import java.sql.Connection;
import java.util.*;

public class CheckDeployEnvStatusWorker implements Job {
    private static final Logger LOG = LoggerFactory.getLogger(CheckDeployEnvStatusWorker.class);
    private final EnvironDAO environDAO;
    private final DeployDAO deployDAO;
    private final UtilDAO utilDAO;
    public static final int  METRICS_AGENT_PORT = 18126;

    public CheckDeployEnvStatusWorker() {

    }

    void processDeploys() throws Exception {
        List<String> envIds = environDAO.getAllEnvIds();
        Collections.shuffle(envIds);
        //count for different deploy states, need emit them to statsboard
        int runningDeploys = 0;
        int failingDeploys = 0;
        int succeedingDeploys = 0;
        int succeededDeploys = 0;
        int abortedDeploys = 0;

        for (String envId : envIds) {
            EnvironBean envBean = environDAO.getById(envId);
            int index = 1;
            int size = 100;
            DeployFilterBean filterBean = new DeployFilterBean();
            filterBean.setEnvIds(Arrays.asList(envBean.getEnv_id()));
            filterBean.setPageIndex(index);
            filterBean.setPageSize(size);
            int maxPages = 50; // This makes us check at most 5000 deploys
            int tocheckPages = maxPages;
            while (tocheckPages-- > 0) {
                DeployQueryFilter filter = new DeployQueryFilter(filterBean);
                DeployQueryResultBean resultBean = deployDAO.getAllDeploys(filter);
                if (resultBean.getTotal() < 1) {
                    LOG.warn("Could not find any previous succeeded deploy in env {}", envBean.getEnv_id());
                }
                for (DeployBean deploy : resultBean.getDeploys()) {
                    if (deploy.getState() == DeployState.RUNNING) {
                        runningDeploys += 1;
                    }
                    else if (deploy.getState() == DeployState.FAILING) {
                        failingDeploys += 1;
                    }
                    else if (deploy.getState() == DeployState.SUCCEEDING) {
                        succeedingDeploys += 1;
                    }
                    else if (deploy.getState() == DeployState.SUCCEEDED) {
                        succeededDeploys += 1;
                    }
                    else if (deploy.getState() == DeployState.ABORTED) {
                        abortedDeploys += 1;
                    }
                }
                index += 1;
                filterBean.setPageIndex(index);
            } 
        }
        emitMetrics(DeployState.RUNNING.toString(), runningDeploys);
        emitMetrics(DeployState.FAILING.toString(), failingDeploys);
        emitMetrics(DeployState.SUCCEEDING.toString(), succeedingDeploys);
        emitMetrics(DeployState.SUCCEEDED.toString(), succeededDeploys);
        emitMetrics(DeployState.ABORTED.toString(), abortedDeploys);
    }

    private void emitMetrics(String status, int stateCount) throws Exception {
        String sentence = "";
        Socket clientSocket = null;
        DataOutputStream outToServer = null;
        try {
            clientSocket = new Socket("127.0.0.1", METRICS_AGENT_PORT);
            outToServer = new DataOutputStream(clientSocket.getOutputStream());
            sentence = String.format("put %s %d %d %s", status, System.currentTimeMillis(), stateCount, " host=localhost");
            outToServer.writeBytes(sentence + '\n');
        } catch (Exception e) {
            LOG.error("TCPClientForMetrics exception {} ", e.toString());
        } finally {
            outToServer.close();
            clientSocket.close();
        }
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        SchedulerContext schedulerContext;

        try {
            schedulerContext = context.getScheduler().getContext();
        } catch (SchedulerException e) {
            LOG.error("Cannot retrieve job context. Aborting execution of CheckDeployEnvStatusWorker.", e);
            return;
        }

        ServiceContext serviceContext = (ServiceContext) schedulerContext.get("serviceContext");
        environDAO = serviceContext.getEnvironDAO();
        deployDAO = serviceContext.getDeployDAO();
        utilDAO = serviceContext.getUtilDAO();

        try {
            LOG.info("Start CheckDeployEnvStatusWorker process...");
            processDeploys();
            LOG.info("Stop CheckDeployEnvStatusWorker process...");
        } catch (Throwable t) {
            LOG.error("CheckDeployEnvStatusWorker execution resulted in an error.", t);
        }
    }

}