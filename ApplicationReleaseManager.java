package com.bocloud.caas.manager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.log4j.Logger;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bocloud.caas.bean.MessageResult;
import com.bocloud.caas.bean.Result;
import com.bocloud.caas.config.SystemConfig;
import com.bocloud.caas.constant.NormalConstant;
import com.bocloud.caas.constant.Status;
import com.bocloud.caas.constant.Type;
import com.bocloud.caas.core.ApplicationReleaseCore;
import com.bocloud.caas.core.domain.CreateStartContainerResult;
import com.bocloud.caas.daemon.PlatformDaemon;
import com.bocloud.caas.entity.App;
import com.bocloud.caas.entity.AppChangeLog;
import com.bocloud.caas.entity.AppGrayReleaseEntity;
import com.bocloud.caas.entity.Cluster;
import com.bocloud.caas.entity.ClusterApp;
import com.bocloud.caas.entity.ClusterResource;
import com.bocloud.caas.entity.ConNet;
import com.bocloud.caas.entity.ConPort;
import com.bocloud.caas.entity.Container;
import com.bocloud.caas.entity.DockerLog;
import com.bocloud.caas.entity.ElasticStrategy;
import com.bocloud.caas.entity.Env;
import com.bocloud.caas.entity.F5Monitor;
import com.bocloud.caas.entity.Host;
import com.bocloud.caas.entity.Image;
import com.bocloud.caas.entity.IpPool;
import com.bocloud.caas.entity.Net;
import com.bocloud.caas.entity.Registry;
import com.bocloud.caas.entity.lbApp;
import com.bocloud.caas.entity.expand.ContainerExpand;
import com.bocloud.caas.exception.SqlException;
import com.bocloud.caas.message.MessagePush;
import com.bocloud.caas.model.AppConfig;
import com.bocloud.caas.model.ApplicationModel;
import com.bocloud.caas.model.ApplicationReleaseModel;
import com.bocloud.caas.model.EnvModel;
import com.bocloud.caas.model.HostModel;
import com.bocloud.caas.model.HostResourceModel;
import com.bocloud.caas.model.ImageModel;
import com.bocloud.caas.model.SimpleContainer;
import com.bocloud.caas.service.AppChangeLogService;
import com.bocloud.caas.service.AppGrayReleaseService;
import com.bocloud.caas.service.AppService;
import com.bocloud.caas.service.ClusterAppService;
import com.bocloud.caas.service.ClusterResourceService;
import com.bocloud.caas.service.ClusterService;
import com.bocloud.caas.service.ConportService;
import com.bocloud.caas.service.ContainerService;
import com.bocloud.caas.service.DockerLogService;
import com.bocloud.caas.service.ElasticStrategyService;
import com.bocloud.caas.service.EnvService;
import com.bocloud.caas.service.F5MonitorService;
import com.bocloud.caas.service.HostService;
import com.bocloud.caas.service.ImageService;
import com.bocloud.caas.service.IpPoolService;
import com.bocloud.caas.service.LbAppService;
import com.bocloud.caas.service.NetService;
import com.bocloud.caas.service.RegistryService;
import com.bocloud.caas.task.AppGrayReleaseTask;
import com.bocloud.caas.util.ResourceLimitUtil;
import com.bocloud.caas.util.TimeUtils;
import com.github.dockerjava.api.model.ContainerPort;

/**
 * date：2015年12月10日 上午9:50:46 project name：cmbc-devops-web
 *
 * @author langzi
 * @version 1.0
 * @since JDK 1.7.0_21 file name：ApplicationReleaseManager.java description：
 */
@Component
public class ApplicationReleaseManager {

    private static final Logger LOGGER = Logger.getLogger(ApplicationReleaseManager.class);

    @Autowired
    private ClusterManager clusterManager;
    @Autowired
    private LoadBalanceManager lbManager;
    // @Autowired
    // private ContainerManager containerManager;
    @Autowired
    private ClusterService clusterService;
    @Autowired
    private AppGrayReleaseService appGrayReleaseService;
    @Autowired
    private HostService hostService;
    @Autowired
    private ContainerService containerService;
    @Autowired
    private ConportService conportService;
    @Autowired
    private ClusterResourceService crService;
    @Autowired
    private ApplicationReleaseCore releaseCore;
    @Autowired
    private AppService appService;
    @Autowired
    private MessagePush messagePush;
    @Autowired
    private ContainerManager containerManager;
    @Autowired
    private EnvService envService;
    @Autowired
    private ClusterAppService caService;
    @Autowired
    private LbAppService laService;
    @Autowired
    private ImageService imgService;
    @Autowired
    private PlatformDaemon platformDaemon;
    @Autowired
    private AppChangeLogService appChangeLogService;
    @Autowired
    private NetService netService;
    @Autowired
    private F5MonitorService f5MonitorService;
    @Autowired
    private RegistryService regiService;
    @Autowired
    private SystemConfig systemConfig;
    @Autowired
    private ElasticStrategyService elasticStrategyService;
    @Autowired
    private IpPoolService ipPoolService;
    @Autowired
    private ResourceLimitUtil resourceLimitUtil;
    

    @Autowired
    private DockerLogService dockerLogService; //记录容器日志

    /**
     * @return
     * @author langzi
     * @version 1.0 2015年12月10日 应用发布
     */
    @SuppressWarnings("finally")
    public Result appRelease(ApplicationReleaseModel model, String accessIp) {

        String releaseModel = "普通发布";
        if (model.getReleaseMode() == 1) {
            releaseModel = "灰度发布";
        }

        // model.setMem(512);
        Image image = null;
        // 获取应用信息
        App app = getAppInfo(model.getTenantId(), model.getAppId());
        int userId = model.getUserId();
        try {
            image = imgService.loadImage(null, model.getImageId());
        } catch (Exception e1) {
            LOGGER.error("获取镜像信息失败！");
            addAppChangeLog(app, image, userId, "应用发布", "error", "获取镜像信息失败", accessIp);
            return new Result(false, "获取镜像信息失败！");
        }
        //防止dop_cluster_resource表数据有占用资源，暂时将所有容器id为0的记录中容器至为null
        try {
            crService.collbackUpdate();
        } catch (Exception e) {
            LOGGER.error("collback update clusterResouce containerId failed", e);
            addAppChangeLog(app, image, userId, "应用发布", "error", "集群资源回滚异常", accessIp);
            return new Result(false, "集群资源回滚异常！");
        }
        // 1.获取集群信息
        Cluster cluster = getClusterInfo(model.getClusterId());
        if (cluster == null) {
            LOGGER.error("获取应用集群信息异常！");
            addAppChangeLog(app, image, userId, "应用发布", "error", "获取应用集群信息失败", accessIp);
            return new Result(false, "未获取应用集群信息!");
        }
        Container con = new Container();
        con.setClusterPort(cluster.getClusterPort());
        pushMessage(userId, new MessageResult(false, "10#" + "获取集群信息(名称:" + cluster.getClusterName() + ")成功。", "应用发布"));
        // 2.检查集群是否正常
        Result result = clusterManager.clusterHealthCheck(cluster.getClusterId());
        if (!result.isSuccess()) {
            LOGGER.error("应用集群状态异常！");
            addAppChangeLog(app, image, userId, "应用发布", "error", "应用集群状态异常", accessIp);
            return new Result(false, "应用集群状态异常，请检查集群状态，再发布应用！");
        }
        pushMessage(userId, new MessageResult(false, "15#" + "集群健康检查(名称:" + cluster.getClusterName() + ")成功。", "应用发布"));
        // 3.获取集群所在主机信息
        Host host = getHostInfo(cluster.getMasteHostId());
        if (host == null) {
            LOGGER.error("获取集群所在主机信息失败！");
            addAppChangeLog(app, image, userId, "应用发布", "error", "获取集群所在主机信息失败", accessIp);
            return new Result(false, "未获取集群所在主机信息！");
        }
        // 判断集群下是否有子节点
        try {
            List<Host> slaveHost = hostService.listHostByClusterId(cluster.getClusterId());
            if (slaveHost.isEmpty()) {
                LOGGER.error("集群中不存在可使用的节点！");
                addAppChangeLog(app, image, userId, "应用发布", "error", "集群中不存在可使用的节点", accessIp);
                return new Result(false, "集群中不存在可使用的节点，请先添加节点，再发布应用！");
            }
        } catch (Exception e) {
            LOGGER.error("Get slave node error", e);
            return new Result(false, "获取集群子节点信息异常");
        }
        pushMessage(userId,
                new MessageResult(false, "30#" + "获取集群节点信息(名称:" + cluster.getClusterName() + ")成功。", "应用发布"));
        // 4.获取应用信息
        if (app == null) {
            LOGGER.error("获取应用信息失败！");
            addAppChangeLog(app, image, userId, "应用发布", "error", "获取应用信息失败", accessIp);
            return new Result(false, "应用发布失败：未获取到应用信息！");
        }
        pushMessage(userId, new MessageResult(false, "40#" + "获取应用信息(名称:" + app.getAppName() + ")成功。", "应用发布"));
        // 5.获取容器最后一条记录
        Integer lastConId = getLastConId();
        if (lastConId == null) {
            LOGGER.error("获取应用实例历史信息失败！");
            addAppChangeLog(app, image, userId, "应用发布", "error", "获取应用实例历史信息失败", accessIp);
            return new Result(false, "应用发布失败：获取应用实例历史信息失败");
        }
        model.setLastConId(lastConId);
        // 根据发布方式获取默认信息 (0:普通发布 1：灰度发布)
        if (model.getReleaseMode() == 0 || model.getReleaseMode() == 1) {
            if (!StringUtils.isEmpty(app.getAppEnv())) {
                // model.setEnv("-e " + app.getAppEnv());
                model.setEnv(app.getAppEnv());
            }
            if (!StringUtils.isEmpty(app.getAppVolumn())) {
                model.setVolume(app.getAppVolumn());
            }
            if (app.getAppParams() != null) {
                /** 修正分号加入到命令行中的问题 */
                model.setParams(app.getAppParams().replace(";", " "));
            }
            if (app.getAppCommand() != null) {
                model.setCommand(app.getAppCommand());
            }
        }
        // 获取环境中的参数
        try {
            Env env = envService.find(model.getEnvId());
            String envparam = env.getEnvParam();
            if (!StringUtils.isEmpty(envparam)) {
                String envArray = " -e " + envparam.replaceAll(";", " -e ");
                envArray += " " + model.getEnv();
                model.setEnv(envArray);
            }
        } catch (Exception e2) {
            LOGGER.error("get env by id[" + model.getEnvId() + "] failed!", e2);
            addAppChangeLog(app, image, userId, "应用发布", "error", "获取环境中的参数失败", accessIp);
            return new Result(false, "获取环境中的参数失败！");
        }
        //网络
        Net net = null;
        if (model.getNetId() > 0) {
            net = netService.getNetByNetId(model.getNetId());
        }
        if (null != net) {
            model.setNetDriver(net.getNetDriver());
        }
        // 如果是集群类型是独享集群，判断资源如果conId没有等于null的，则资源不足不执行扩展操作，如果资源足够先抢占资源
        List<Integer> isNullCrConIds = new ArrayList<Integer>();
        List<HostResourceModel> hrmList = new ArrayList<HostResourceModel>();
        // 进行 应用发布、扩容资源判断处理 共享集群 判断CPU MEM; 独享集群 判断CPU
        resourceLimitUtil.checkResource(host, cluster);
        if (cluster.getResType() == Type.CLUSTER_RES.PRIVATE.ordinal()) {
            int releaseNum = model.getReleaseNum();
            List<Host> hostList = new ArrayList<Host>();
            try {
                hostList = hostService.listByResourceConIdIsNullAndCluterId(cluster.getClusterId());
            } catch (SqlException e1) {
                LOGGER.error("获取可使用独享集群资源的主机信息失败！");
                addAppChangeLog(app, image, userId, "应用发布", "error", "获取可使用独享集群资源的主机信息失败！", accessIp);
                return new Result(false, "获取可使用独享集群资源的主机信息失败！");
            }
            if (hostList.isEmpty()) {
                LOGGER.error("集群资源不够！");
                addAppChangeLog(app, image, userId, "应用发布", "error", "集群资源不够", accessIp);
                return new Result(false, "集群资源不够，请扩充资源再发布应用");
            }
            int maxMem = 0;
            try {
                List<ClusterResource> clusterResources = crService.selectByClusterId(cluster.getClusterId());
                for (ClusterResource clusterResource : clusterResources) {
                    isNullCrConIds.add(clusterResource.getId());
                }
            } catch (Exception e) {
                LOGGER.error("获取独享集群信息失败！");
                addAppChangeLog(app, image, userId, "应用发布", "error", "获取独享集群信息失败", accessIp);
                return new Result(false, "获取独享集群信息失败！");
            }
            for (int i = 0; i < releaseNum; i++) {
                List<ClusterResource> clusterResources = new ArrayList<ClusterResource>();
                try {
                    clusterResources = crService.selectByClusterId(cluster.getClusterId());
                } catch (Exception e) {
                    LOGGER.error("获取独享集群信息失败！");
                    addAppChangeLog(app, image, userId, "应用发布", "error", "获取独享集群信息失败", accessIp);
                    return new Result(false, "获取独享集群信息失败！");
                }
                if (clusterResources.isEmpty()) {
                    LOGGER.error("集群资源不够！");
                    addAppChangeLog(app, image, userId, "应用发布", "error", "集群资源不够", accessIp);
                    return new Result(false, "集群资源不够，请扩充资源再发布应用");
                }
                //获取所有可用资源主机中内存最大的主机
                Host maxResourceHost = new Host();
                for (Host hos : hostList) {
                    //判断主机的cpu是否够用，如果不够用则直接跳过该主机
                    List<ClusterResource> clusterResourcesByHost = crService.listResourceByHostId(hos.getHostId());
                    if (clusterResourcesByHost.size() < model.getCpu()) {
                        continue;
                    }
                    List<Container> containers = new ArrayList<Container>();
                    try {
                        containers = containerService.listContainersByHostId(hos.getHostId());
                    } catch (Exception e) {
                        LOGGER.error("根据独享节点获取容器异常！", e);
                        addAppChangeLog(app, image, userId, "应用发布", "error", "根据独享节点获取容器异常", accessIp);
                        return new Result(false, "根据独享节点获取容器异常！");
                    }
                    int mem = hos.getHostMem();
                    if (hos.getUsedMem() != null) {
                        mem -= hos.getUsedMem();
                    }
                    for (Container container : containers) {
                        if (null != container.getConMem() && container.getConMem() > 0) {
                            mem -= container.getConMem();
                        }
                    }
                    if (mem >= maxMem) {
                        maxMem = mem;
                        maxResourceHost = hos;
                    }
                }
                try {
                    //因为最大内存都不足使用则所有主机内存都没有满足的情况
                    if (maxMem < model.getMem()) {
                        LOGGER.error("集群主机：" + maxResourceHost.getHostName() + "资源不够！");
                        crService.collbackUpdate();
                        addAppChangeLog(app, image, userId, "应用发布", "error", "集群资源不够", accessIp);
                        return new Result(false, "集群资源不够，请扩充资源再发布应用");
                    }
                } catch (Exception e) {
                    LOGGER.error("集群资源不够");
                    return new Result(false, "集群资源不够，请扩充资源再发布应用");
                }
                
                //按照独享资源中资源最大的主机获取资源集合
                List<ClusterResource> clusterResourcesByHost = crService.listResourceByHostId(maxResourceHost.getHostId());
                String hostRealName = getHostInfo(maxResourceHost.getHostId()).getHostRealName();
                Integer[] cpuCores = new Integer[model.getCpu()];
                long mem = model.getMem();
                for (int j = 0; j < model.getCpu(); j++) {
                    ClusterResource cr = clusterResourcesByHost.get(j);
                    cpuCores[j] = cr.getCpuId();
                    cr.setConId(0);
                    //如果使用了资源则将内存追加到主机内存中
                    // 预先抢占资源
                    crService.update(cr);

                }
                maxMem -= model.getMem();
                if (maxResourceHost.getUsedMem() == null) {
                    maxResourceHost.setUsedMem(0);
                }
                //主机占用的mem
                maxResourceHost.setUsedMem(maxResourceHost.getUsedMem() + model.getMem());
                //网络类型，如果选择实现macvlan网络类型执行以下内容
                String macVlanNet = null;
                String macVlanIp = null;
                Integer ipPoolId = null;
                //如果选择了macvlan网络
                if (null != net && net.getNetDriver() == 5) {
                    List<IpPool> ipPools = null;
                    try {
                        IpPool ipPool = new IpPool();
                        ipPool.setMacvlanId(net.getNetId());
                        ipPools = ipPoolService.selectAllFree(ipPool);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (null == ipPools || ipPools.isEmpty() || ipPools.size() < releaseNum - i) {
                        //执行失败回滚
                        for (HostResourceModel hostResourceModel : hrmList) {
                            ipPoolService.changeToFreeByIp(hostResourceModel.getMacVlanIp());
                        }
                        LOGGER.error("MACVLAN网络配置所需IP不够，支持发布[" + ipPools.size() + "]个实例。请确认后重新发布应用");
                        return new Result(false, "MACVLAN网络配置所需IP不够，支持发布[" + ipPools.size() + "]个实例。请确认后重新发布应用");
                    } else {
                        macVlanNet = ipPools.get(0).getMacVlanNet();
                        macVlanIp = ipPools.get(0).getIp();
                        ipPoolId = ipPools.get(0).getId();
                        IpPool ipPool = new IpPool();
                        ipPool.setId(ipPools.get(0).getId());
                        try {
                            ipPoolService.changeToUnfree(ipPool);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                HostResourceModel hrm = new HostResourceModel(cpuCores, hostRealName, maxResourceHost.getHostId(), mem, macVlanNet, macVlanIp, ipPoolId);
                hrmList.add(hrm);
            }
            if (hrmList.size() < model.getReleaseNum()) {
                try {
                    crService.collbackUpdate();
                } catch (Exception e) {
                    LOGGER.error("collback update clusterResouce containerId failed", e);
                } finally {
                    addAppChangeLog(app, image, userId, "应用发布", "error", "集群资源不够", accessIp);
                    return new Result(false, "集群资源不够，请扩充资源再发布应用");
                }
            }
            model.setHrmList(hrmList);
        } else {
            //非独享时计算macvlan地址
            for (int i = 0; i < model.getReleaseNum(); i++) {
                int releaseNum = model.getReleaseNum();
                //网络类型，如果选择实现macvlan网络类型执行以下内容
                String macVlanNet = null;
                String macVlanIp = null;
                Integer ipPoolId = null;
                if (null != net && 5 == net.getNetDriver()) {
                    List<IpPool> ipPools = null;
                    try {
                        IpPool ipPool = new IpPool();
                        ipPool.setMacvlanId(net.getNetId());
                        ipPools = ipPoolService.selectAllFree(ipPool);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (null == ipPools || ipPools.isEmpty() || ipPools.size() < releaseNum - i) {
                        //执行失败回滚
                        for (HostResourceModel hostResourceModel : hrmList) {
                            ipPoolService.changeToFreeByIp(hostResourceModel.getMacVlanIp());
                        }
                        LOGGER.error("MACVLAN网络配置所需IP不够，支持发布[" + ipPools.size() + "]个实例。请确认后重新发布应用");
                        return new Result(false, "MACVLAN网络配置所需IP不够，支持发布[" + ipPools.size() + "]个实例。请确认后重新发布应用");
                    } else {
                        macVlanNet = ipPools.get(0).getMacVlanNet();
                        macVlanIp = ipPools.get(0).getIp();
                        ipPoolId = ipPools.get(0).getId();
                        IpPool ipPool = new IpPool();
                        ipPool.setId(ipPools.get(0).getId());
                        try {
                            ipPoolService.changeToUnfree(ipPool);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                HostResourceModel hrm = new HostResourceModel(null, null, 0, 0, macVlanNet, macVlanIp, ipPoolId);
                hrmList.add(hrm);
            }
            if (hrmList.size() < model.getReleaseNum()) {
                try {
                    crService.collbackUpdate();
                } catch (Exception e) {
                    LOGGER.error("collback update clusterResouce containerId failed", e);
                } finally {
                    addAppChangeLog(app, image, userId, "应用发布", "error", "IP资源不够", accessIp);
                    return new Result(false, "集群IP资源不够，请扩充资源再发布应用");
                }
            }
            model.setHrmList(hrmList);
        }


        // 6.发布应用
        pushMessage(userId, new MessageResult(false, "50#" + "应用启动开始。", "应用发布"));
        List<CreateStartContainerResult> releaseResults = releaseCore.releaseApp(host.getHostIp(), host.getHostUser(),
                host.getHostPwd(), cluster.getClusterPort(), model);

        if (releaseResults.isEmpty()) {
            try {
                crService.collbackUpdate();
            } catch (Exception e) {
                LOGGER.error("collback update clusterResouce containerId failed", e);
                addAppChangeLog(app, image, userId, "应用发布", "error", "集群资源回滚异常", accessIp);
                return new Result(false, "集群资源回滚异常！");
            }
            LOGGER.error("docker服务器和集群服务器异常！");
            addAppChangeLog(app, image, userId, "应用发布", "error", "docker服务器和集群服务器异常", accessIp);
            return new Result(false, "应用发布失败：服务器异常，请检查docker服务器和集群是否正常！");
        }
        pushMessage(userId, new MessageResult(false, "70#" + "应用启动成功。", "应用发布"));
        // 容器信息保存数据库
        String conids = "";

        for (CreateStartContainerResult releaseResult : releaseResults) {
            if (!releaseResult.isSuccess()) {
                LOGGER.error(releaseResult.getMessage());
                // 如果是独享集群释放资源
                if (cluster.getResType() == Type.CLUSTER_RES.PRIVATE.ordinal()) {
                    for (Integer isNullCrConId : isNullCrConIds) {
                        ClusterResource cr = crService.select(isNullCrConId);
                        cr.setConId(null);
                        int res = crService.updateConIdById(cr);
                        if (res <= 0) {
                            LOGGER.error("缩容失败，释放独享资源异常！");
                            addAppChangeLog(app, image, userId, "扩容", "error", "缩容失败，释放独享资源异常！", accessIp);
                            return new Result(false, "缩容失败，释放独享资源异常！");
                        }
                    }
                    addAppChangeLog(app, image, userId, "应用发布", "error", releaseResult.getMessage(), accessIp);
                }
                //如果是macvlan执行回滚
                //执行失败回滚
                for (HostResourceModel hostResourceModel : hrmList) {
                    ipPoolService.changeToFreeByIp(hostResourceModel.getMacVlanIp());
                }
                return new Result(false, releaseResult.getMessage());
            }

            com.github.dockerjava.api.model.Container container = releaseResult.getContainer();
            ApplicationReleaseModel arm = new ApplicationReleaseModel();
            String conName = container.getNames()[0];
            BeanUtils.copyProperties(model, arm);
            arm.setConName(conName);
            arm.setAppStatus(Status.APP_STATUS.UNDEFINED.ordinal());
            arm.setMonitorStatus(Status.MONITOR_STATUS.UNDEFINED.ordinal());
            model.setRandomPath(releaseResult.getRandomPath());
            //容器id
            Integer conId = addContainerInfo(model, host, cluster, container, releaseResult.getCommand());

            if (null != net) {
                // 插入到con_net表：容器与网络对应关系
                ConNet con_net = new ConNet();
                con_net.setConId(conId);
                con_net.setNetId(net.getNetId());
                con_net.setAppId(model.getAppId());
                netService.createConNetDate(con_net);
            }

            conids += conId + ",";
        }

        // 判断该应用是否已经使用网络，未使用插入表中
        if (null != net) {
            // 更新应用表
            App application = appService.getAppByAppId(model.getAppId());
            if (null == application.getNetId() || (application.getNetId()).equals("")
                    || (application.getNetId()) == 0) {
                application.setNetId(net.getNetId());
                int num = appService.updateAppNetId(application);
                if (num == 0) {
                    return new Result(false, "数据库中应用更新网络ID失败！");
                }

                net.setNetOccupy((byte) 1);
                int updateNetOccupyNum = netService.updateNetOccupy(net);
                if (updateNetOccupyNum <= 0) {
                    return new Result(false, "数据库更新网络是否被使用出错");
                }
            }

        }

        conids = conids.substring(0, conids.length() - 1);
        //因为容器启动成功后，应用并不能保证启动完成，此时加入负载有时会有bug，所以将负载在LoadBalanceDaemon中实现
        try {
            List<ClusterApp> cas = caService.listClusterAppsByAppIdAndClusterId(model.getAppId(), model.getClusterId());
            if (cas.size() == 0 || cas == null) {
                ClusterApp ca = new ClusterApp();
                ca.setAppId(model.getAppId());
                ca.setClusterId(model.getClusterId());
                caService.addClusterApp(ca);
            }
            pushMessage(userId, new MessageResult(false, "80#" + "保存应用集群信息。", "应用发布"));
            lbApp la = new lbApp();
            la.setAppId(model.getAppId());
            la.setLbId(model.getBalanceId());
            // 清除原有数据
            laService.remove(la);
            // 添加新数据
            laService.add(la);
            pushMessage(userId, new MessageResult(false, "90#" + "保存应用负载信息。", "应用发布"));
        } catch (Exception e) {
            LOGGER.error(e);
        }

        pushMessage(userId, new MessageResult(false, "100#" + "应用发布完成。", "应用发布"));
        // 如果是灰度发布，发布成功后删除被替换的应用版本
        if (model.getReleaseMode() == 1) {
            addAppChangeLog(app, image, userId, "灰度发布", "success", "应用发布成功", accessIp);
            //更新镜像灰度发布状态
            image.setGrayreleaseType(Byte.parseByte("1"));
            if (model.getReplaceControlIps() != null && !model.getReplaceControlIps().equals("")) {
                image.setGrayreleaseIp(model.getReplaceControlIps());
            } else {
                image.setGrayreleaseIp(null);
            }
            if (model.getControlIps() != null && !model.getControlIps().equals("")) {
                image.setGrayreleaseIp(model.getControlIps());
            } else if (image.getGrayreleaseIp() == null || image.getGrayreleaseIp().equals("")){
                image.setGrayreleaseIp(null);
            }
            try {
                imgService.update(image);
            } catch (Exception e) {
                LOGGER.error(e);
            }
            //释放资源
            Result res = getReplacedContainer(model.getOldImageId(), model.getReleaseNum());
            if (cluster.getResType() == Type.CLUSTER_RES.PRIVATE.ordinal()) {
                String[] conId = res.getMessage().split(",");
                Integer[] conIds = new Integer[conId.length];
                for (int i = 0; i < conIds.length; i++) {
                    conIds[i] = Integer.parseInt(conId[i]);
                }
                crService.updateByConId(conIds);
            }
            return res;
        }
        //*****************记录容器状态日志    2017-03-17  by   jingziming   begin  *************//
        if (conids != null && conids.indexOf(",") != -1) {
            String[] containers = conids.split(",");
            for (String id : containers) {
                DockerLog dockerLog = new DockerLog();
                dockerLog.setAppId(app.getAppId());
                dockerLog.setAppName(app.getAppName());
                dockerLog.setAppTag(image.getImageTag());
                dockerLog.setConId(Integer.parseInt(id));
                dockerLog.setLogCreatetime(new Date());//生成时间
                dockerLog.setConStatus(Status.CONTAINER.UP.ordinal());//容器状态（0:删除1:运行2:停止3:未知）
                dockerLog.setLogAction(releaseModel + ":发布应用");
                dockerLog.setLogResult("成功");
                try {
                    dockerLogService.save(dockerLog);
                } catch (Exception e) {
                    LOGGER.error(e);
                }
            }
        }
        //*****************记录容器状态日志    2017-03-17  by   jingziming   end  *************//
        addAppChangeLog(app, image, userId, "应用发布", "success", "应用发布成功", accessIp);
        // 若指定负载为F5，则将监控项入库
        Map<String, Object> lbMap = lbManager.detail(model.getBalanceId());
        if (lbMap.size() > 0) {
            String lbType = lbMap.get("lbType").toString();
            if ("F5".equals(lbType)) {
                F5Monitor f5Monitor = new F5Monitor();
                f5Monitor.setAppId(model.getAppId());
                f5Monitor.setMonitorName(model.getMonitorName());
                f5Monitor.setMonitorInterval(model.getMonitorInterval());
                f5Monitor.setMonitorReceiveString(model.getMonitorReceiveString());
                f5Monitor.setMonitorTemplateName(model.getMonitorTemplateName());
                f5Monitor.setMonitorTimeout(model.getMonitorTimeout());
                f5Monitor.setMonitorUri(model.getMonitorUri());
                f5MonitorService.insert(f5Monitor);
            }
        }
        return new Result(true, conids);
    }

    /**
     * 灰度发布前的准备操作
     *
     * @author DZG
     * @since 2017年3月24日
     */
    public ApplicationReleaseModel grayReleasePrepare(ApplicationReleaseModel model) {
        if (model.getReleaseMode() == 1) {
            // 先查询 该应用是否已经有 灰度发布任务
            try {
                AppGrayReleaseEntity are = appGrayReleaseService.getOne(model.getAppId(), model.getEnvId(),
                        model.getImageId(), model.getUserId(), model.getTenantId(), 1);
                if (are != null) {
                    ApplicationReleaseModel arm = new ApplicationReleaseModel();
                    arm.setAppId(-1);
                    arm.setEnvId(-1);// 验证存在 灰度发布任务
                    return arm;
                }
            } catch (SqlException e1) {
                LOGGER.error("查询灰度任务出错！", e1);
            }

            // 设置按照比例滚动发布
            // model中包含了 先行比例的数量 需要保留 尚未发布的数量（需要待发布）
            model.setReleaseNum(model.getReplaceNum());
        }

        return model;
    }

    /**
     * 灰度发布处理
     * <p>
     * <pre>
     * 灰度发布有2种
     * 1.直接替换发布数量，直接调用发布方法即可
     * 2.比例发布，先按照一定比例先行灰度发布一部分，其余定时调用发布方法（生成特殊的定时任务）
     *
     * 由于还需要有：
     *
     * 1.可以启停该定时任务
     * 2.可以回滚该灰度任务
     * 3.可以一次性执行完灰度任务(这个需要比较新老版本容器的数量)
     * </pre>
     *
     * @author DZG
     * @since 2017年3月17日
     */
    public ApplicationReleaseModel grayRelease(ApplicationReleaseModel model, String accessIp) {
        int releaseNewNum = 0;
        String controlIp = "";
        if (model.getReleaseMode() == 1 && model.getAutoExecute() == 1) {
            releaseNewNum = model.getOldConNum() - model.getReplaceNum();
            // 将需要待滚动发布的内容保存
            // 保存时间间隔 间隔时间的单位 0 分 1时 2日 默认为分
            int intervalTime = 0;
            switch (model.getTimeLevel()) {
                case 0:
                    intervalTime = model.getIntervalTime();
                    break;
                case 1:
                    intervalTime = model.getIntervalTime() * 60;
                    break;
                case 2:
                    intervalTime = model.getIntervalTime() * 60 * 24;
                    break;
                default:
                    intervalTime = model.getIntervalTime();
                    break;
            }
            controlIp = model.getReplaceControlIps() == "" ? model.getControlIps() : model.getReplaceControlIps();
            AppGrayReleaseEntity appGray = new AppGrayReleaseEntity(model.getAppId(), model.getOldImageId(),
                    model.getImageId(), model.getEnvId(), model.getUserId(), model.getTenantId(), accessIp,
                    model.getStepSize(), intervalTime, model.getAutoExecute(), releaseNewNum, model.getOldConNum(),
                    controlIp, 1, model.getClusterId());
            try {
                int i = appGrayReleaseService.create(appGray);
                // 保存滚动灰度发布信息成功，创建定时任务
                if (i > 0) {
                	model.setAccessIp(accessIp);
                    createGrayReleaseTask(appGray, model);
                } else {
                    return null;
                }
            } catch (SqlException e) {
                LOGGER.error("插入灰度发布任务失败！", e);
                return null;
            }
        }
        return model;
    }

    /**
     * 创建定时的 滚动灰度发布
     *
     * @author DZG
     * @since 2017年3月17日
     */
    private Result createGrayReleaseTask(AppGrayReleaseEntity appGray, ApplicationReleaseModel model) {
        try {
            LOGGER.info("Create grayReleaseTask begin");
            Timer timer = new Timer();
            // 保存的时间 是以分钟保存的
            Long time = appGray.getIntervalTime() * 60 * 1000L;
            Date beginDate = TimeUtils.addMinuteForDate(new Date(), appGray.getIntervalTime());
            if (time != 0) {
                timer.schedule(
						new AppGrayReleaseTask(appGray, timer, true, this, appGrayReleaseService, model, imgService,
								containerService),
						beginDate, time);
            }
            LOGGER.info("Create grayReleaseTask end");
            return new Result(true, "开始进入灰度滚动发布");
        } catch (Exception e) {
            LOGGER.error("灰度滚动发布异常！", e);
            return new Result(false, "灰度滚动发布异常！");
        }
    }

    /**
     * @param appId
     * @param imageId
     * @param envId
     * @param imgChangeId
     * @return
     * @author langzi
     * @version 1.0 2016年6月13日 应用版本变更
     */
    public synchronized Result appChange(int appId, int imageId, int envId, int imgChangeId, int userId, int tenantId,
                                         String accessIp) {
        platformDaemon.stopAppDaemon(appId, envId);
        // 1.获取启动容器的信息
        Container container = new Container();
        container.setAppId(appId);
        container.setConImgid(imageId);
        container.setEnvId(envId);
        List<Container> cons = new ArrayList<>();
        Image image = null;
        App app = getAppInfo(tenantId, appId);
        try {
            image = imgService.loadImage(null, imgChangeId);
        } catch (Exception e2) {
            platformDaemon.startAppDaemon(appId, envId);
            LOGGER.error("获取镜像失败！");
            addAppChangeLog(app, image, userId, "版本变更", "error", "获取镜像失败", accessIp);
            return new Result(false, "添加应用变更记录异常！");
        }
        try {
            cons = containerService.listAllContainer(container);
        } catch (Exception e) {
            platformDaemon.startAppDaemon(appId, envId);
            LOGGER.error("获取启动容器的信息失败！");
            addAppChangeLog(app, image, userId, "版本变更", "error", "获取启动容器的信息失败", accessIp);
            return new Result(false, "获取启动容器的信息失败！");
        }
        Container tmp = new Container();
        int changeNum = cons.size();
        // 2.获取新镜像的id
        String imgUrl = image.getImageName() + ":" + image.getImageTag();
        // 3.构建新的command
        String[] conIds = new String[cons.size()];
        String[] changeCommand = new String[cons.size()];
        //创建随机路径路径文件命名规则（时间戳）
        String[] randomPath = new String[cons.size()];
        for (int i = 0; i < cons.size(); i++) {
            tmp = cons.get(0);
            conIds[i] = String.valueOf(cons.get(i).getConId());
            String[] containerCommand = cons.get(i).getConStartCommand().split("-P");
            changeCommand[i] = containerCommand[0] + " -P " + imgUrl;
            randomPath[i] = String.valueOf(System.currentTimeMillis() + i);
            changeCommand[i].replace(cons.get(i).getRandomPath(), randomPath[i]);
            LOGGER.info("start command is :" + changeCommand[i]);
        }
        // 获取集群信息
        Cluster cluster = null;
        Host host = null;
        try {
            cluster = clusterService.getCluster(envId, appId);
            host = getHostInfo(cluster.getMasteHostId());
        } catch (Exception e) {
            platformDaemon.startAppDaemon(appId, envId);
            LOGGER.error("获取集群信息失败！");
            addAppChangeLog(app, image, userId, "版本变更", "error", "获取集群信息失败", accessIp);
            return new Result(false, "获取集群信息失败！");
        }
        // 构建应用发布的模型
        ApplicationReleaseModel model = new ApplicationReleaseModel();
        model.setAppId(appId);
        model.setImageId(imgChangeId);
        model.setEnvId(envId);
        model.setUserId(userId);
        model.setTenantId(tenantId);
        lbApp lbApp = laService.selectByAppId(appId);
        model.setBalanceId(lbApp.getLbId());
        model.setHttpType(tmp == null ? null : tmp.getHttpType());
        model.setCheckURL(tmp == null ? null : tmp.getCheckURL());
        // 4.启动新实例
        List<CreateStartContainerResult> releaseResults = releaseCore.appRelease(host.getHostIp(),
                cluster.getClusterPort(), host.getHostUser(), host.getHostPwd(), changeNum, changeCommand, randomPath);
        // releaseCore.appExtend(host.getHostIp(),
        // cluster.getClusterPort(), changeNum, imgUrl, changeCommand);
        /*
		 * List<CreateStartContainerResult> releaseResults =
		 * releaseCore.appExtend(host.getHostIp(), cluster.getClusterPort(),
		 * changeNum, imgUrl, changeCommand);
		 */

        for (CreateStartContainerResult releaseResult : releaseResults) {
            if (!releaseResult.isSuccess()) {
                platformDaemon.startAppDaemon(appId, envId);
                LOGGER.error(releaseResult.getMessage());
                return new Result(false, releaseResult.getMessage());
            }
            com.github.dockerjava.api.model.Container con = releaseResult.getContainer();
            ApplicationReleaseModel arm = new ApplicationReleaseModel();
            String conName = con.getNames()[0];
            BeanUtils.copyProperties(model, arm);
            arm.setConName(conName);
            arm.setAppStatus(Status.APP_STATUS.UNDEFINED.ordinal());
            arm.setMonitorStatus(Status.MONITOR_STATUS.UNDEFINED.ordinal());
            model.setRandomPath(releaseResult.getRandomPath());
            addContainerInfo(model, host, cluster, con, releaseResult.getCommand());
        }

        // 5.更新负载均衡
        //新增实例不可即时更新负载，守护进程会自动更新
//		lbManager.updateLBofContainer(conIds, 2);
        // 6.停止旧实例
        containerManager.stopContainer(conIds);
        // 7.删除旧实例
        Result result = containerManager.removeContainer(conIds);
        platformDaemon.startAppDaemon(appId, envId);
        addAppChangeLog(app, image, userId, "版本变更", "success", "应用版本变更成功", accessIp);
        if (result.isSuccess()) {
            return new Result(true, "应用版本变更成功！");
        }
        return result;
    }

    /**
     * @param appId
     * @param imageId
     * @param envId
     * @param exceptionContainerId
     * @param extendNum
     * @return
     * @author langzi
     * @version 1.0 2016年6月13日 应用扩容
     */
    public synchronized Result appExtend(Integer appId, Integer imageId, Integer envId, Integer exceptionContainerId, int extendNum, int userId,
                                         int tenantId, String accessIp, String action) {
        // 1.获取需要启动容器的信息
        Container container = new Container();
        List<Container> cons = new ArrayList<>();
        String[] conIds;
        // 扩容的容器id，用于更新负载
        String[] extendConIds = new String[1];
        // 构建新的command
        Image img = new Image();
        App app = getAppInfo(tenantId, appId);
        //防止dop_cluster_resource表数据有占用资源，暂时将所有容器id为0的记录中容器至为null
        try {
            crService.collbackUpdate();
        } catch (Exception e) {
            LOGGER.error("collback update clusterResouce containerId failed", e);
            addAppChangeLog(app, img, userId, "应用发布", "error", "集群资源回滚异常", accessIp);
            return new Result(false, "集群资源回滚异常！");
        }
        // 获取集群信息
        Cluster cluster = null;
        Host host = null;
        try {
            cluster = clusterService.getCluster(envId, appId);
            if (cluster != null) {
                host = getHostInfo(cluster.getMasteHostId());
            }
        } catch (Exception e) {
            LOGGER.error("获取集群信息失败！");
            addAppChangeLog(app, img, userId, "扩容", "error", "获取集群信息失败", accessIp);
            return new Result(false, "获取集群信息失败！");
        }

        container.setAppId(appId);
        container.setConImgid(imageId);
        container.setEnvId(envId);
        try {
            cons = containerService.listAllContainer(container);
        } catch (Exception e) {
            LOGGER.error("获取容器失败！");
            addAppChangeLog(app, img, userId, "扩容", "error", "获取容器失败", accessIp);
            return new Result(false, "获取容器失败！");
        }

        if (cons.isEmpty() && exceptionContainerId == null) {
            LOGGER.error("获取容器失败！");
            addAppChangeLog(app, img, userId, "扩容", "error", "获取容器失败", accessIp);
            return new Result(false, "获取容器失败！");
        }
        //判断相同应用实例发布所在同一主机的数量------start----------mayunaho20170220
        Map<Integer, Integer> hostMap = new HashMap<Integer, Integer>();
        for (Container conTemp : cons) {
            if (hostMap.containsKey(conTemp.getHostId())) {
                hostMap.put(conTemp.getHostId(), hostMap.get(conTemp.getHostId()) + 1);
            } else {
                hostMap.put(conTemp.getHostId(), 1);
            }
        }
        //判断相同应用实例发布所在同一主机的数量------end----------mayunaho20170220
        String httpType = null;
        String checkURL = null;
        List<Integer> isNullCrConIds = new ArrayList<Integer>();
        Integer[] cpuCores;
        // 构建应用发布的模型
        ApplicationReleaseModel model = new ApplicationReleaseModel();
        List<HostResourceModel> hrmList = new ArrayList<HostResourceModel>();
        // 进行 应用发布、扩容资源判断处理 共享集群 判断CPU MEM; 独享集群 判断CPU
        resourceLimitUtil.checkResource(host, cluster);
        //可执行的扩容命令集合
        List<Map<String, String>> commandMaps = new ArrayList<Map<String, String>>();
        if (cluster != null && cluster.getResType().equals((byte) Type.CLUSTER_RES.PRIVATE.ordinal())) {
            int maxMem = 0;
            //获取所有可用资源主机中内存最大的主机
            Host maxResourceHost = new Host();
            try {
                List<ClusterResource> clusterResources = crService.selectByClusterId(cluster.getClusterId());
                for (ClusterResource clusterResource : clusterResources) {
                    isNullCrConIds.add(clusterResource.getId());
                }
            } catch (Exception e) {
                LOGGER.error("获取独享集群信息失败！");
                addAppChangeLog(app, img, userId, "扩容", "error", "获取独享集群信息失败", accessIp);
                return new Result(false, "获取独享集群信息失败！");
            }
            for (int i = 0; i < extendNum; i++) {
                Map<String, String> commandMap = new HashMap<String, String>();
                List<ClusterResource> clusterResources = new ArrayList<ClusterResource>();
                try {
                    clusterResources = crService.selectByClusterId(cluster.getClusterId());
                } catch (Exception e) {
                    LOGGER.error("获取独享集群信息失败！");
                    addAppChangeLog(app, img, userId, "扩容", "error", "获取独享集群信息失败", accessIp);
                    return new Result(false, "获取独享集群信息失败！");
                }
                if (clusterResources.isEmpty()) {
                    try {
                        crService.collbackUpdate();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    LOGGER.error("集群资源不够！");
                    addAppChangeLog(app, img, userId, "扩容", "error", "集群资源不够", accessIp);
                    if (hrmList.size() == 0) {
                        return new Result(false, "集群资源不够，请扩充资源再发布应用");
                    }
                }
                Container con = new Container();
                if (exceptionContainerId != null) {
                    try {
                        con = containerService.selectContainerById(exceptionContainerId);
                    } catch (SqlException e) {
                        LOGGER.error("获取异常容器失败！");
                        return new Result(false, "获取异常容器失败！");
                    }
                } else {
                    con = cons.get(0);
                }
                try {
                    img = imgService.loadImage(null, con.getConImgid());
                } catch (Exception e2) {
                    LOGGER.error("获取集群信息失败！");
                    addAppChangeLog(app, img, userId, "扩容", "error", "获取集群信息失败", accessIp);
                    return new Result(false, "获取集群信息失败！");
                }
                //容器网络，如果是macvlan网络，需要通过网络查找相关的IP池
                ConNet conNet = new ConNet();
                conNet.setAppId(appId);
                conNet.setConId(con.getConId());
                conNet = netService.selectConNet(conNet);
                Net net = null;
                if (null != conNet && null != conNet.getNetId()) {
                    net = netService.getNetByNetId(conNet.getNetId());
                }

                imageId = img.getImageId();
                //扩容时获取http和url
                httpType = con.getHttpType();
                checkURL = con.getCheckURL();
                //应用实例的租户id作为扩容的租户id
                tenantId = con.getTenantId();
                String command = con.getConStartCommand();
                //获取需要扩容的容器的内存和cou数量
                //model.setMem(Integer.parseInt(command.substring(command.indexOf("-m ") + 3, command.indexOf("m -d"))));
                model.setMem(con.getConMem());
                //int mem = 512;
                String cpuComand = command.substring(command.indexOf("-cpus="), command.indexOf("-m ") - 1);
                //model.setCpu(cpuComand.split(",").length);
                model.setCpu(con.getConCpu());
                List<Host> hostList = new ArrayList<Host>();
                try {
                    hostList = hostService.listByResourceConIdIsNullAndCluterId(cluster.getClusterId());
                } catch (SqlException e1) {
                    LOGGER.error("获取可使用独享集群资源的主机信息失败！");
                    addAppChangeLog(app, img, userId, "应用发布", "error", "获取可使用独享集群资源的主机信息失败！", accessIp);
                    return new Result(false, "获取可使用独享集群资源的主机信息失败！");
                }
                if (hostList.isEmpty()) {
                    LOGGER.error("集群资源不够！");
                    addAppChangeLog(app, img, userId, "扩容", "error", "集群资源不够", accessIp);
                    return new Result(false, "集群资源不够，请扩充资源再发布应用");
                }
                //如果上次主机资源被占用，此次重新计算maxmem
                if (null != maxResourceHost && null != maxResourceHost.getHostId() && maxResourceHost.getHostId() > 0) {
                    int tmpMaxMem = 0;
                    for (Host hos : hostList) {
                        if (maxResourceHost.getHostId() == hos.getHostId()) {
                            tmpMaxMem = maxMem;
                        }
                    }
                    if (tmpMaxMem > 0) {
                        maxMem = tmpMaxMem;
                    } else {
                        maxMem = 0;
                    }
                    //如果已选择主机实例数超过2，过滤掉。
                    if (hostMap.containsKey(maxResourceHost.getHostId()) && hostMap.get(maxResourceHost.getHostId()) >= 2) {
                        maxMem = 0;
                        maxResourceHost = null;
                    }
                }
                Host lastHost = null;
                for (Host hos : hostList) {
                    //如果此主机已发布相同应用实例2个及以上，跳过此主机------start----------mayunaho20170220
                    //实例限制,弹性伸缩策略启动
                    if (null != app.getElasticId() && app.getElasticStatus() == Status.ELASTIC_STATUS.OPEN.ordinal()) {
                        ElasticStrategy strategy = elasticStrategyService.load(app.getElasticId());
                        if (null != strategy && null != strategy.getExpandHostAppLimit() && strategy.getExpandHostAppLimit() > 0) {
                            if (hostMap.containsKey(hos.getHostId()) && hostMap.get(hos.getHostId()) >= strategy.getExpandHostAppLimit()) {
                                //如果最优资源主机在限定实例数量时被过滤掉，那么不要再考虑此主机的资源
                                if (null != maxResourceHost && null != maxResourceHost.getHostId() && maxResourceHost.getHostId() == hos.getHostId()) {
                                    maxResourceHost = null;
                                    maxMem = 0;
                                }
                                LOGGER.info("弹性伸缩跳过主机！" + hos.getHostIp() + "  应用ID:" + appId);
                                continue;
                            }
                        }
                    }
                    //如果此主机已发布相同应用实例2个及以上，跳过此主机------end----------mayunaho20170220
                    //判断主机的cpu是否够用，如果不够用则直接跳过该主机
                    List<ClusterResource> clusterResourcesByHost = crService.listResourceByHostId(hos.getHostId());
                    if (clusterResourcesByHost.size() < model.getCpu()) {
                        continue;
                    }
                    List<Container> containers = new ArrayList<Container>();
                    try {
                        containers = containerService.listContainersByHostId(hos.getHostId());
                    } catch (Exception e) {
                        LOGGER.error("根据独享节点获取容器异常！", e);
                        addAppChangeLog(app, img, userId, "扩容", "error", "根据独享节点获取容器异常", accessIp);
                        return new Result(false, "根据独享节点获取容器异常！");
                    }
                    int mem = hos.getHostMem();
                    if (hos.getUsedMem() != null) {
                        mem -= hos.getUsedMem();
                    }
                    for (Container memContainer : containers) {
                        if (null != memContainer.getConMem() && memContainer.getConMem() > 0) {
                            mem -= memContainer.getConMem();
                        }
                    }
                    if (mem >= maxMem) {
                        maxMem = mem;
                        maxResourceHost = hos;
                        //如果上一个主机不是资源最优的，需要把上一个主机的选中次数减掉1
                        if (hostMap.containsKey(hos.getHostId())) {
                            hostMap.put(hos.getHostId(), hostMap.get(hos.getHostId()) + 1);
                        } else {
                            hostMap.put(hos.getHostId(), 1);
                        }
                        if (null != lastHost && lastHost.getHostId() != hos.getHostId() &&
                                hostMap.containsKey(lastHost.getHostId()) && hostMap.get(lastHost.getHostId()) > 0) {
                            hostMap.put(lastHost.getHostId(), hostMap.get(lastHost.getHostId()) - 1);
                        }
                        lastHost = hos;
                    }
                }
                //如果未选定主机
                if (null == lastHost) {
                    LOGGER.error("弹性伸缩未计算出合适的主机;应用ID:" + appId);
                    continue;
                }
                if (maxMem == 0) {
                    LOGGER.error("集群主机：资源分配计算失败，请检查主机资源后重新执行！应用ID:" + appId);
                    try {
                        crService.collbackUpdate();
                    } catch (Exception e) {
                        LOGGER.error("集群资源不够;应用ID:" + appId);
                        return new Result(false, "集群资源不够，请扩充资源再发布应用");
                    }
                    return new Result(false, "集群主机：资源分配计算失败，请检查主机资源后重新执行！");
                }
                try {
                    //因为最大内存都不足使用则所有主机内存都没有满足的情况
                    if (maxMem < model.getMem()) {
                        LOGGER.error("集群主机：" + maxResourceHost.getHostName() + "资源不够！应用ID:" + appId);
                        crService.collbackUpdate();
                        addAppChangeLog(app, img, userId, "扩容", "error", "集群资源不够", accessIp);
                        return new Result(false, "集群资源不够，请扩充资源再发布应用");
                    }
                } catch (Exception e) {
                    LOGGER.error("集群资源不够;应用ID" + appId);
                    return new Result(false, "集群资源不够，请扩充资源再发布应用");
                }
                if (null == maxResourceHost || maxResourceHost.getHostId() == null) {
                    LOGGER.error("弹性伸缩未计算出合适的主机;应用ID:" + appId);
                    continue;
                }
                LOGGER.warn("弹性伸缩选定符合的主机！" + maxResourceHost.getHostIp() + " ;应用ID:" + appId);
                //按照独享资源中资源最大的主机获取资源集合
                List<ClusterResource> clusterResourcesByHost = crService.listResourceByHostId(maxResourceHost.getHostId());
                //获取节点名
                String nodeCommand = command.substring(command.indexOf("constraint:node=="), command.indexOf(" -v /etc/localtime"));
                String newNodeCommand = "constraint:node==" + maxResourceHost.getHostRealName();
                String newCpuCommand = "-cpus=";
                LOGGER.info("抢占资源开始;应用ID:" + appId);
                long mem = model.getMem();
                cpuCores = new Integer[model.getCpu()];
                for (int j = 0; j < model.getCpu(); j++) {
                    ClusterResource cr = clusterResourcesByHost.get(j);
                    newCpuCommand += cr.getCpuId();
                    if (j < model.getCpu() - 1) {
                        newCpuCommand += ",";
                    }
                    cpuCores[j] = cr.getCpuId();
                    cr.setConId(0);
                    // 预先抢占资源
                    crService.update(cr);
                    LOGGER.info("抢占主机" + maxResourceHost.getHostIp() + " 的 cpu 核：" + cr.getCpuId() + " 应用ID:" + appId);
                }
                LOGGER.info("抢占资源结束;应用ID:" + appId);
                maxMem -= model.getMem();
                if (maxResourceHost.getUsedMem() == null) {
                    maxResourceHost.setUsedMem(0);
                }
                //主机占用的mem
                maxResourceHost.setUsedMem(maxResourceHost.getUsedMem() + model.getMem());

                command = command.replace(cpuComand, newCpuCommand);
                command = command.replace(nodeCommand, newNodeCommand);
                //替换mem
                command = command.replace(model.getMem() + "m", mem + "m");
                String randomPath = String.valueOf(System.currentTimeMillis() + i);
                commandMap.put("randomPath", randomPath);
                if (null != con.getRandomPath()) {
                    command = command.replace(con.getRandomPath(), randomPath);
                }
                //网络类型，如果选择实现macvlan网络类型执行以下内容
                String macVlanNet = null;
                String macVlanIp = null;
                Integer ipPoolId = null;
                //如果选择了macvlan网络，替换独立IP
                if (null != net && net.getNetDriver() == 5 && command.contains("--net=") && command.contains("--ip=")) {
                    model.setNetDriver(net.getNetDriver());
                    String ipCommnad = command.substring(command.indexOf("--ip="), command.indexOf("-P ") - 1);
                    List<IpPool> ipPools = null;
                    try {
                        IpPool ipPool = new IpPool();
                        ipPool.setMacvlanId(net.getNetId());
                        ipPools = ipPoolService.selectAllFree(ipPool);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (null == ipPools || ipPools.isEmpty() || null == ipPools.get(0) || null == ipPools.get(0).getId()) {
                        LOGGER.error("MACVLAN网络配置所需IP不够，请确认后重新发布应用");
                        continue;
                    } else {
                        macVlanNet = ipPools.get(0).getMacVlanNet();
                        macVlanIp = ipPools.get(0).getIp();
                        ipPoolId = ipPools.get(0).getId();
                        IpPool ipPool = new IpPool();
                        ipPool.setId(ipPools.get(0).getId());
                        try {
                            ipPoolService.changeToUnfree(ipPool);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    //替换ip
                    command = command.replace(ipCommnad, "--ip=" + macVlanIp);
                }
                commandMap.put("command", command);

                LOGGER.info("执行命令行：" + command + " 应用ID:" + appId);
                HostResourceModel hrm = new HostResourceModel(cpuCores, maxResourceHost.getHostRealName(), maxResourceHost.getHostId(), mem, macVlanNet, macVlanIp, ipPoolId);
                hrmList.add(hrm);
                commandMaps.add(commandMap);
            }
        } else if (exceptionContainerId != null) { // 如果exceptionContainerId不为空，那么就是需要单独扩展这个异常的容器，否则按个数做普通扩展
            conIds = new String[1];
            conIds[0] = String.valueOf(exceptionContainerId);
            Map<String, String> commandMap = new HashMap<String, String>();
            String randomPath = String.valueOf(System.currentTimeMillis());
            commandMap.put("randomPath", randomPath);
            try {
                container = containerService.selectContainerById(exceptionContainerId);
                //容器网络，如果是macvlan网络，需要通过网络查找相关的IP池
                ConNet conNet = new ConNet();
                conNet.setAppId(appId);
                conNet.setConId(container.getConId());
                conNet = netService.selectConNet(conNet);
                Net net = null;
                if (null != conNet && null != conNet.getNetId()) {
                    net = netService.getNetByNetId(conNet.getNetId());
                }
                img = imgService.loadImage(null, container.getConImgid());
                imageId = img.getImageId();

                //网络类型，如果选择实现macvlan网络类型执行以下内容
                String macVlanNet = null;
                String macVlanIp = null;
                Integer ipPoolId = null;
                //集群调度修改后，判断应用集群是否有改变，如果之前应用部署集群与当前集群不符合，新的应用部署到新的集群中，执行命令需要替换
                String command = null;
                if (container.getClusterIp().equals(host.getHostIp()) && container.getClusterPort().equals(cluster.getClusterPort())) {
                    command = container.getConStartCommand();
                } else {
                    String newClusterURL = "tcp://" + host.getHostIp() + ":" + cluster.getClusterPort();
                    String oldClusterURL = "tcp://" + container.getClusterIp() + ":" + container.getClusterPort();
                    command = container.getConStartCommand().replace(oldClusterURL, newClusterURL);
                }
                if (null == command) {
                    LOGGER.error("获取异常容器执行命令失败！");
                    return new Result(false, "获取异常容器执行命令失败！");
                }
                if (null != net && net.getNetDriver() == 5 && command.contains("--net=") && command.contains("--ip=")) {
                    model.setNetDriver(net.getNetDriver());
                    String ipCommnad = command.substring(command.indexOf("--ip="), command.indexOf("-P ") - 1);
                    List<IpPool> ipPools = null;
                    try {
                        IpPool ipPool = new IpPool();
                        ipPool.setMacvlanId(net.getNetId());
                        ipPools = ipPoolService.selectAllFree(ipPool);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (null == ipPools || ipPools.isEmpty() || null == ipPools.get(0) || null == ipPools.get(0).getId()) {
                        //执行失败回滚
                        for (HostResourceModel hostResourceModel : hrmList) {
                            if (hostResourceModel.getMacVlanIp().equals(ipCommnad)) {
                                ipPoolService.changeToFreeByIp(hostResourceModel.getMacVlanIp());
                            }
                        }
                        LOGGER.error("MACVLAN网络配置所需IP不够，请确认后重新发布应用");
                        return new Result(false, "MACVLAN网络配置所需IP不够，请确认后重新发布应用！");
                    } else {
                        macVlanNet = ipPools.get(0).getMacVlanNet();
                        macVlanIp = ipPools.get(0).getIp();
                        ipPoolId = ipPools.get(0).getId();
                        IpPool ipPool = new IpPool();
                        ipPool.setId(ipPools.get(0).getId());
                        try {
                            ipPoolService.changeToUnfree(ipPool);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    //替换ip
                    command = command.replace(ipCommnad, "--ip=" + macVlanIp);
                }
                LOGGER.info("start command is :" + command);
                HostResourceModel hrm = new HostResourceModel(null, null, 0, 0, macVlanNet, macVlanIp, ipPoolId);
                hrmList.add(hrm);
                commandMap.put("command", command);
                //扩容时获取http和url
                httpType = container.getHttpType();
                checkURL = container.getCheckURL();
                //应用实例的租户id作为扩容的租户id
                tenantId = container.getTenantId();
            } catch (Exception e) {
                LOGGER.error("获取异常容器失败！");
                return new Result(false, "获取异常容器失败！");
            }
            commandMaps.add(commandMap);
        } else {
            //共享集群扩容执行
            try {
                img = imgService.loadImage(null, imageId);
            } catch (Exception e) {
                LOGGER.error("获取镜像失败！");
                addAppChangeLog(app, img, userId, "扩容", "error", "获取镜像失败", accessIp);
                return new Result(false, "获取镜像失败！");
            }

            conIds = new String[cons.size()];
            for (int i = 0; i < cons.size(); i++) {
                conIds[i] = String.valueOf(cons.get(i).getConId());
            }
            for (int i = 0; i < extendNum; i++) {
                Map<String, String> commandMap = new HashMap<String, String>();
                String randomPath = String.valueOf(System.currentTimeMillis() + i);
                commandMap.put("randomPath", randomPath);
                //集群调度修改后，判断应用集群是否有改变，如果之前应用部署集群与当前集群不符合，新的应用部署到新的集群中，执行命令需要替换
                Container tmp = cons.get(0);
                //容器网络，如果是macvlan网络，需要通过网络查找相关的IP池
                ConNet conNet = new ConNet();
                conNet.setAppId(appId);
                conNet.setConId(tmp.getConId());
                conNet = netService.selectConNet(conNet);
                Net net = null;
                if (null != conNet && null != conNet.getNetId()) {
                    net = netService.getNetByNetId(conNet.getNetId());
                }
                //扩容时获取http和url
                httpType = tmp.getHttpType();
                checkURL = tmp.getCheckURL();
                //应用实例的租户id作为扩容的租户id
                tenantId = tmp.getTenantId();
                //网络类型，如果选择实现macvlan网络类型执行以下内容
                String macVlanNet = null;
                String macVlanIp = null;
                Integer ipPoolId = null;
                String command = null;
                if (tmp.getClusterIp().equals(host.getHostIp()) && tmp.getClusterPort().equals(cluster.getClusterPort())) {
                    command = tmp.getConStartCommand();
                } else {
                    String newClusterURL = "tcp://" + host.getHostIp() + ":" + cluster.getClusterPort();
                    String oldClusterURL = "tcp://" + tmp.getClusterIp() + ":" + tmp.getClusterPort();
                    command = tmp.getConStartCommand().replace(oldClusterURL, newClusterURL);
                    if (null != tmp.getRandomPath()) {
                        command = command.replace(tmp.getRandomPath(), randomPath);
                    }
                }
                if (null == command) {
                    LOGGER.error("获取异常容器执行命令失败！");
                    return new Result(false, "获取异常容器执行命令失败！");
                }
                if (null != net && net.getNetDriver() == 5 && command.contains("--net=") && command.contains("--ip=")) {
                    model.setNetDriver(net.getNetDriver());
                    String ipCommnad = command.substring(command.indexOf("--ip="), command.indexOf("-P ") - 1);
                    List<IpPool> ipPools = null;
                    try {
                        IpPool ipPool = new IpPool();
                        ipPool.setMacvlanId(net.getNetId());
                        ipPools = ipPoolService.selectAllFree(ipPool);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (null == ipPools || ipPools.isEmpty() || null == ipPools.get(0) || null == ipPools.get(0).getId()) {
                        LOGGER.error("MACVLAN网络配置所需IP不够，请确认后重新发布应用");
                        continue;
                    } else {
                        macVlanNet = ipPools.get(0).getMacVlanNet();
                        macVlanIp = ipPools.get(0).getIp();
                        ipPoolId = ipPools.get(0).getId();
                        IpPool ipPool = new IpPool();
                        ipPool.setId(ipPools.get(0).getId());
                        try {
                            ipPoolService.changeToUnfree(ipPool);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    //替换ip
                    command = command.replace(ipCommnad, "--ip=" + macVlanIp);
                }
                LOGGER.info("start command is :" + command);
                HostResourceModel hrm = new HostResourceModel(null, null, 0, 0, macVlanNet, macVlanIp, ipPoolId);
                hrmList.add(hrm);
                commandMap.put("command", command);
                commandMaps.add(commandMap);

            }
        }
        model.setHrmList(hrmList);
        model.setAppId(appId);
        model.setImageId(imageId);
        model.setEnvId(envId);
        model.setUserId(userId);
        model.setTenantId(tenantId);
        lbApp lbApp = laService.selectByAppId(appId);
        model.setBalanceId(lbApp.getLbId());
        model.setHttpType(httpType);
        model.setCheckURL(checkURL);
        String[] command = new String[commandMaps.size()];
        String[] randomPath = new String[commandMaps.size()];
        int realNum = commandMaps.size();
        for (int i = 0; i < commandMaps.size(); i++) {
            command[i] = commandMaps.get(i).get("command");
            randomPath[i] = commandMaps.get(i).get("randomPath");
        }
        // 4.启动新实例
        List<CreateStartContainerResult> releaseResults = releaseCore.appRelease(host.getHostIp(),
                cluster.getClusterPort(), host.getHostUser(), host.getHostPwd(), realNum, command, randomPath);
		/*
		 * List<CreateStartContainerResult> releaseResults =
		 * releaseCore.appExtend(host.getHostIp(), cluster.getClusterPort(),
		 * extendNum, imgUrl, changeCommand);
		 */
        for (CreateStartContainerResult releaseResult : releaseResults) {
            if (!releaseResult.isSuccess()) {
                LOGGER.error(releaseResult.getMessage());
                // 如果发布实例失败释放独享占用的资源，collbackUpdate可能会修改其他用户占用的资源
                if (isNullCrConIds.size() > 0) {
                    //bug50--集群资源不足，获取的数组数量小于扩容数量，造成空指针
                    int realNumTmp = isNullCrConIds.size() < realNum ? isNullCrConIds.size() : realNum;
                    for (int i = 0; i < realNumTmp; i++) {
                        ClusterResource cr = crService.select(isNullCrConIds.get(i));
                        cr.setConId(null);
                        // 预先抢占资源
                        int result = crService.updateConIdById(cr);
                        if (result <= 0) {
                            LOGGER.error("扩容失败，释放独享资源异常！");
                            addAppChangeLog(app, img, userId, "扩容", "error", "扩容失败，释放独享资源异常！", accessIp);
                            return new Result(false, "扩容失败，释放独享资源异常！");
                        }
                    }
                }
                try {
                    String startcommand = releaseResult.getCommand();
                    String ipCommnad = startcommand.substring(startcommand.indexOf("--ip="), startcommand.indexOf("-P ") - 1).replace("--ip=", "");
                    for (HostResourceModel hostResourceModel : hrmList) {
                        if (hostResourceModel.getMacVlanIp().equals(ipCommnad)) {
                            ipPoolService.changeToFreeByIp(hostResourceModel.getMacVlanIp());
                        }
                    }
                    addAppChangeLog(app, img, userId, "扩容", "error", releaseResult.getMessage(), accessIp);
                    return new Result(false, releaseResult.getMessage());
                } catch (Exception e) {
                    return new Result(false, releaseResult.getMessage());
                }
            }

            com.github.dockerjava.api.model.Container con = releaseResult.getContainer();
            ApplicationReleaseModel arm = new ApplicationReleaseModel();
            String conName = con.getNames()[0];
            BeanUtils.copyProperties(model, arm);
            arm.setConName(conName);
            arm.setAppStatus(Status.APP_STATUS.UNDEFINED.ordinal());
            arm.setMonitorStatus(Status.MONITOR_STATUS.UNDEFINED.ordinal());
            model.setRandomPath(releaseResult.getRandomPath());
            int conId = addContainerInfo(model, host, cluster, con, releaseResult.getCommand());
            extendConIds[0] = String.valueOf(conId);
            //*****************记录容器状态日志    2017-03-17  by   jingziming   begin  *************//
            DockerLog dockerLog = new DockerLog();
            dockerLog.setAppId(app.getAppId());
            dockerLog.setAppName(app.getAppName());
            dockerLog.setAppTag(img.getImageTag());//根据imageId 查询镜像版本：
            dockerLog.setConId(conId);
            dockerLog.setLogCreatetime(new Date());//生成时间
            dockerLog.setConStatus(Status.CONTAINER.UP.ordinal());//容器状态（0:删除1:运行2:停止3:未知）
            dockerLog.setLogAction(action);
            dockerLog.setLogResult("成功");
            try {
                dockerLogService.save(dockerLog);
            } catch (Exception e) {
                LOGGER.error(e);
            }
            //*****************记录容器状态日志    2017-03-17  by   jingziming   end  *************//
            // 扩容命令中含有net参数，即扩容时指定了网络
            if (command[0].contains("--net=")) {
                ConNet con_net = new ConNet();
                con_net.setConId(conId);
                con_net.setAppId(model.getAppId());
                con_net.setNetId(app.getNetId());
                int result = netService.createConNetDate(con_net);
                if (result <= 0) {
                    LOGGER.error("扩容失败，添加网络记录异常！");
                    // 释放资源
                    if (isNullCrConIds.size() > 0) {
                        for (int i = 0; i < realNum; i++) {
                            ClusterResource cr = crService.select(isNullCrConIds.get(i));
                            cr.setConId(null);
                            // 预先抢占资源
                            int res = crService.updateConIdById(cr);
                            if (res <= 0) {
                                LOGGER.error("扩容失败，释放独享资源异常！");
                                addAppChangeLog(app, img, userId, "扩容", "error", "扩容失败，释放独享资源异常！", accessIp);
                                return new Result(false, "扩容失败，释放独享资源异常！");
                            }
                        }
                    }
                    addAppChangeLog(app, img, userId, "扩容", "error", "扩容失败，添加网络记录异常！", accessIp);
                    return new Result(false, "扩容失败，添加网络记录异常！");
                }
            }
        }
        if (0 == realNum) {
            LOGGER.warn("由于资源限制,扩容操作未执行！");
            addAppChangeLog(app, img, userId, "扩容", "error", "由于资源限制,扩容操作未执行！", accessIp);
            return new Result(true, "由于资源限制,扩容操作未执行！");
        } else if (extendNum > realNum) {
            LOGGER.info("由于资源限制,成功发布[" + realNum + "]个实例,[" + (extendNum - realNum) + "]个实例未创建。");
            addAppChangeLog(app, img, userId, "扩容", "success",
                    "由于资源限制,成功发布[" + realNum + "]个实例,[" + (extendNum - realNum) + "]个实例未创建。", accessIp);
            return new Result(true, "由于资源限制,成功发布[" + realNum + "]个实例,[" + (extendNum - realNum) + "]个实例未创建。");
        } else {
            LOGGER.info("成功发布[" + realNum + "]个实例！");
            addAppChangeLog(app, img, userId, "扩容", "success", "成功发布[" + realNum + "]个实例！", accessIp);
            return new Result(true, "成功发布[" + realNum + "]个实例！");
        }
        // 5.更新扩容成功的容器负载均衡
        //新增时负载不更新，由守护判断更新-mayh20170214
//		LOGGER.error(JSONObject.toJSONString(extendConIds));
//		Result result = lbManager.updateLBofContainer(extendConIds, 0);
//		if (result.isSuccess()) {
//			addAppChangeLog(app, img, userId, "扩容", "success", "扩容成功", accessIp);
//		} else {
//			addAppChangeLog(app, img, userId, "扩容", "error", "扩容失败，更新负载异常！", accessIp);
//		}
    }

    /**
     * @param appId
     * @param imageId
     * @param envId
     * @param reduceNum
     * @return
     * @author langzi
     * @version 1.0 2016年6月13日 应用缩容
     */
    public Result appReduce(int appId, int imageId, int envId, List<Integer> containerIds, int reduceNum, int userId,
                            int tenantId, String accessIp, String action) {
        //先停守护
        platformDaemon.stopAppDaemon(appId, envId);
        // 1.获取启动容器的信息
        Container container = new Container();
        container.setAppId(appId);
        container.setConImgid(imageId);
        container.setEnvId(envId);
        List<Container> cons = new ArrayList<>();

        Image image = null;
        App app = getAppInfo(tenantId, appId);
        try {
            image = imgService.loadImage(null, imageId);
        } catch (Exception e2) {
            LOGGER.error("获取镜像失败！");
            addAppChangeLog(app, image, userId, "缩容", "error", "获取镜像失败", accessIp);
            platformDaemon.startAppDaemon(appId, envId);
            return new Result(false, "获取镜像失败！");
        }
        try {
            cons = containerService.listAllContainer(container);
        } catch (Exception e) {
            LOGGER.error("获取容器失败！");
            addAppChangeLog(app, image, userId, "缩容", "error", "获取容器失败", accessIp);
            platformDaemon.startAppDaemon(appId, envId);
            return new Result(false, "获取容器失败！");
        }
        if(cons == null || cons.size() == 0){
        	return new Result(false, "获取容器失败！");
        }
        String[] conIds = new String[reduceNum];
        // 如果containerIds有值，则是需要收缩的特定容器（比如需要收缩异常的容器）
        if (containerIds != null && containerIds.size() > 0) {
            for (int i = 0; i < containerIds.size(); i++) {
                conIds[i] = String.valueOf(containerIds.get(i));
            }
        } else {
            for (int i = 0; i < reduceNum; i++) {
                conIds[i] = String.valueOf(cons.get(i).getConId());
            }
        }
        // 1.获取需要停止的容器的信息
        List<SimpleContainer> simCons = null;
        try {
            simCons = containerService.selectContainerUuid(conIds);
            if (simCons.isEmpty()) {
                return new Result(false, "停止容器失败：未获取到容器信息！");
            }
            String[] conUuids = new String[simCons.size()];
            for (int i = 0; i < simCons.size(); i++) {
                SimpleContainer sc = simCons.get(i);
                conUuids[i] = sc.getContainerUuid();
            }
            ContainerExpand ce = new ContainerExpand();
            ce.setConUuids(conUuids);
            ce.setConPower((byte) Status.POWER.OFF.ordinal());
            containerService.modifyConStatus(ce);
        } catch (Exception e) {
            LOGGER.error("Get container infos error by ID(" + conIds + ")", e);
            return new Result(false, "停止容器失败：获取容器详细信息失败！");
        }
        // 5.更新负载均衡
        lbManager.updateLBofContainer(conIds, 2);
        //睡两秒
        try {
            Thread.sleep(1000 * 2);
        } catch (InterruptedException e) {
        }
        // 6.停止旧实例
        containerManager.stopContainer(conIds);

        // 在删除旧实例之前查询表中数据

        // 7.删除旧实例
        Result result = containerManager.removeContainer(conIds);
        addAppChangeLog(app, image, userId, "缩容", "success", "缩容成功", accessIp);

        /**
         * //缩容成功之后，如果缩容的数量等于当前容器数量，证明该应用下已经没有容器了此时更新数据库：app表中更新该应用未使用net，net表中相应的网络更改为“未被使用状态”
         * int conNum = 0;
         *
         * List<ConNet> conNetList = netService.selectNumByAppId(appId);
         * List<String> conIdInConNetList = new ArrayList<String>(); for (String
         * conId : conIds) { ConNet con_net = new ConNet();
         * con_net.setConId(Integer.parseInt(conId)); con_net.setAppId(appId);
         * ConNet con_net_result = netService.selectConNet(con_net); if(null !=
         * con_net_result){ conIdInConNetList.add(conId); conNum++; } }
         *
         * if(conNum < conNetList.size()){ //删除con_net重缩容数据 for (String conId :
         * conIdInConNetList) { int deleteNum =
         * netService.deleteFromConNetByConId(Integer.parseInt(conId));
         * if(deleteNum <= 0){ return new Result(false,"删除con_net表中数据失败"); } }
         * }else{ //删除con_net数据并更新app表以及net表 for (String conId :
         * conIdInConNetList) { int deleteNum =
         * netService.deleteFromConNetByConId(Integer.parseInt(conId));
         * if(deleteNum <= 0){ return new Result(false,"删除con_net表中数据失败"); } }
         *
         * //更新app表
         *
         * App selectApp = appService.getAppByAppId(appId);
         *
         * App updateApp = new App(); updateApp.setNetId(0);
         * updateApp.setAppId(appId); int updateNum =
         * appService.updateAppNetId(updateApp); if(updateNum <= 0){ new
         * Result(false,"缩容：更新应用网络ID失败"); }
         *
         * //更新net表 Net updateNet = new Net();
         * updateNet.setNetId(selectApp.getNetId());
         * updateNet.setNetOccupy((byte)0); int updateNetNum =
         * netService.updateNetOccupy(updateNet); if(updateNetNum <= 0){ return
         * new Result(false,"更新net表网络是否被使用失败"); } }
         */
        platformDaemon.startAppDaemon(appId, envId);
        //*****************记录容器状态日志    2017-03-17  by   jingziming   begin  *************//
        for (String id : conIds) {
            DockerLog dockerLog = new DockerLog();
            dockerLog.setAppId(app.getAppId());
            dockerLog.setAppName(app.getAppName());
            dockerLog.setAppTag(image.getImageTag());
            dockerLog.setConId(Integer.parseInt(id));
            dockerLog.setLogCreatetime(new Date());//生成时间
            dockerLog.setConStatus(Status.CONTAINER.DELETE.ordinal());//容器状态（0:删除1:运行2:停止3:未知）
            dockerLog.setLogAction(action);
            dockerLog.setLogResult("成功");
            try {
                dockerLogService.save(dockerLog);
            } catch (Exception e) {
                LOGGER.error(e);
            }
        }
        //*****************记录容器状态日志    2017-03-17  by   jingziming   end  *************//
        return result;
    }

    public Result appModifyConfig(int appId, int envId, int imgId, int cpuNum, int mem, int userId,
                                  int tenantId, String accessIp) {
        // 获取集群
        Cluster cluster = null;
        Image image = null;
        App app = getAppInfo(tenantId, appId);
        try {
            image = imgService.loadImage(tenantId, imgId);
        } catch (Exception e3) {
            LOGGER.error("获取镜像失败！");
            addAppChangeLog(app, image, userId, "配置变更", "error", "获取镜像失败", accessIp);
            return new Result(false, "获取镜像失败！");
        }
        try {
            cluster = clusterService.getCluster(envId, appId);
        } catch (Exception e1) {
            LOGGER.error("list cluster error", e1);
            addAppChangeLog(app, image, userId, "配置变更", "error", "获取集群信息失败", accessIp);
            return new Result(false, "获取集群信息失败！");
        }
        // 根据appId和envId，获取conuuid，hostId，clusterId,
        Container container = new Container();
        container.setAppId(appId);
        container.setEnvId(envId);
        container.setConImgid(imgId);
        List<Container> containers = null;
        try {
            containers = containerService.listAllContainer(container);
        } catch (Exception e) {
            LOGGER.error("list all container error", e);
            addAppChangeLog(app, image, userId, "配置变更", "error", "获取容器失败", accessIp);
            return new Result(false, "获取容器失败！");
        }
        List<AppConfig> acs = new ArrayList<AppConfig>();
        // 循环所有符合容器，分别执行变更操作
        for (Container con : containers) {
            // 判断是收缩，还是扩展
            List<ClusterResource> usedCrs = crService.listResourceByConId(con.getConId());
            int oldCpuNum = usedCrs.size();
            AppConfig ac = new AppConfig();
            if (oldCpuNum > cpuNum) {
                Iterator<ClusterResource> crIterator = usedCrs.iterator();
                Integer[] removeCpuIds = new Integer[oldCpuNum - cpuNum];
                String cpus = "";
                int i = 0, j = 0;
                while (crIterator.hasNext()) {
                    ClusterResource cr = crIterator.next();
                    if (i < cpuNum) {
                        cpus += cr.getCpuId() + ",";
                        crIterator.remove();
                        i++;
                    } else {
                        removeCpuIds[j] = cr.getCpuId();
                        crIterator.remove();
                        j++;
                    }
                }
                crService.updateConIdByHostIdAndCpuIds(con.getHostId(), removeCpuIds, null);
                if (cpus.length() > 1) {
                    ac.setConCpu(cpus.substring(0, cpus.length() - 1));
                }
            } else {
                // 擴展资源数
                int expendNum = cpuNum - oldCpuNum;
                try {
                    Host host = hostService.loadHost(con.getHostId());
                    int hostId = host.getHostId();
                    List<ClusterResource> crs = crService.listResourceByHostId(hostId);
                    if (expendNum > crs.size()) {
                        LOGGER.error("资源不足，无法完成配置变更操作");
                        crService.collbackUpdate();
                        addAppChangeLog(app, image, userId, "配置变更", "error", "集群资源不足", accessIp);
                        return new Result(false, "资源不足，无法完成配置变更操作");
                    }
                    Iterator<ClusterResource> crsIterator = crs.iterator();
                    // 获取已有的cpu和内存
                    String cpus = "";
                    for (ClusterResource clusterResource : usedCrs) {
                        cpus += clusterResource.getCpuId() + ",";
                    }
                    Integer[] cpuIds = new Integer[expendNum];
                    int i = 0;
                    while (crsIterator.hasNext()) {
                        if (i < cpuIds.length) {
                            ClusterResource cResource = crsIterator.next();
                            cpus += cResource.getCpuId() + ",";
                            cpuIds[i] = cResource.getCpuId();
                            crsIterator.remove();
                        } else {
                            break;
                        }
                        i++;
                    }
                    // 如果cpuIds.length > 0，预先抢占资源，否则cpu没有更改不需要修改集群资源
                    if (cpuIds.length > 0) {
                        crService.updateConIdByHostIdAndCpuIds(hostId, cpuIds, con.getConId());
                    }
                    if (cpus.length() > 1) {
                        ac.setConCpu(cpus.substring(0, cpus.length() - 1));
                    }
                } catch (Exception e) {
                    LOGGER.error("list all host error", e);
                    addAppChangeLog(app, image, userId, "配置变更", "error", "获取主机失败", accessIp);
                    return new Result(false, "配置变更失败！资源不足！");
                }
            }
            ac.setMem(mem);
            ac.setConUuid(con.getConUuid());
            ac.setConStartCommand(con.getConStartCommand());
            acs.add(ac);
        }
        Host host = getHostInfo(cluster.getMasteHostId());
        Result result = releaseCore.appConfigModify(host.getHostIp(), cluster.getClusterPort(), acs);
        //修改dop_cluster_resource表中存储的集群CPU MEM数据，对应的 dop_container中的 容器CPU MEM也要修改，不然缩扩容会按照dop_container表中修改
        if (result.isSuccess()) {
//			acs.stream().forEach(ac -> ac.setConCpu(cpuNum + ""));
            acs.stream().forEach(ac -> ac.setNewMem(mem));
            Result resultc = containerManager.modifyContainers(acs);
            if (resultc.isSuccess()) {
                addAppChangeLog(app, image, userId, "配置变更", "success", "配置变更成功", accessIp);
            } else {
                addAppChangeLog(app, image, userId, "配置变更", "error", "配置变更失败" + result.getMessage(), accessIp);
                return new Result(false, result.getMessage());
            }
        } else {
            addAppChangeLog(app, image, userId, "配置变更", "error", "配置变更失败" + result.getMessage(), accessIp);
            return new Result(false, result.getMessage());
        }
        return new Result(true, "配置变更成功");
    }


    /**
     * @param clusterId
     * @return
     * @author langzi
     * @version 1.0 2015年12月10日
     */
    private Cluster getClusterInfo(Integer clusterId) {
        try {
            return clusterService.getCluster(clusterId);
        } catch (Exception e) {
            LOGGER.error("get cluster by cluster id failed", e);
            return null;
        }
    }

    /**
     * @param hostId
     * @return
     * @author langzi
     * @version 1.0 2015年12月10日
     */
    private Host getHostInfo(Integer hostId) {
        try {
            return hostService.loadHost(hostId);
        } catch (Exception e) {
            LOGGER.error("Get host by host id error", e);
            return null;
        }
    }

    /**
     * @param appId
     * @return
     * @author langzi
     * @version 1.0 2015年12月10日
     */
    private App getAppInfo(Integer tenantId, Integer appId) {
        try {
            return appService.findAppById(tenantId, appId);
        } catch (Exception e) {
            LOGGER.error("get application by appidfalied！", e);
            return null;
        }
    }

    /**
     * @return
     * @author langzi
     * @version 1.0 2015年12月10日
     */
    private Integer getLastConId() {
        try {
            return containerService.getLastConId();
        } catch (Exception e) {
            LOGGER.error("Get last conId error", e);
            return null;
        }
    }

    /**
     * @param model
     * @param host
     * @param cluster
     * @param con
     * @param command
     * @return
     * @author langzi
     * @version 1.0 2015年11月25日
     */
    private Integer addContainerInfo(ApplicationReleaseModel model, Host host, Cluster cluster,
                                     com.github.dockerjava.api.model.Container con, String command) {
        Container container = new Container();
        container.setConUuid(con.getId());
        // 容器名称
        container.setConName(model.getConName());
        container.setConImgid(model.getImageId());
        if (con.getStatus().contains("Up")) {
            container.setConPower((byte) Status.POWER.UP.ordinal());
        } else {
            container.setConPower((byte) Status.POWER.OFF.ordinal());
        }
        container.setAppStatus((byte) model.getAppStatus());
        container.setMonitorStatus((byte) model.getMonitorStatus());
        container.setConDesc(model.getAppDesc());
        container.setConCpu(model.getCpu());
        container.setConMem(model.getMem());
        //因为容器启动成功后应用并不能保证启动完成，此时加入负载会有bug，所以容器启动并且成功后默认是未加入负载，
        //方便负载的守护判断未加入负载的容器加入负载
        container.setBalanceStatus((byte) Status.BALANCE_STATUS.NOT_JOIN_LB.ordinal());
        container.setConStatus((byte) Status.CONTAINER.UP.ordinal());
        container.setConStartCommand(command);
        container.setConStartParam(model.getParams());
        container.setAppId(model.getAppId());
        container.setClusterIp(host.getHostIp());
        container.setClusterPort(cluster.getClusterPort());
        container.setConCreator(model.getUserId());
        container.setConCreatetime(new Date());
        container.setMonitorHostId(model.getMonitorHostId());
        container.setEnvId(model.getEnvId());
        container.setTenantId(model.getTenantId());
        container.setConType((byte) Type.CONTAINER_TYPE.APP.ordinal());
        container.setBalanceId(model.getBalanceId());
        container.setCheckURL(model.getCheckURL());
        container.setHttpType(model.getHttpType());
        container.setRandomPath(model.getRandomPath());
        // 添加端口
        ContainerPort[] ports = con.getPorts();
        ContainerPort conPort = null;
        int hostId = 0;
        ConPort conPort2 = new ConPort();
        String conHostIp = null;
        //macvlan网络
        if (null != model.getNetDriver() && 5 == model.getNetDriver()) {
            // 获取服务端口,只保留应用服务端口
            App app = getAppInfo(0, model.getAppId());
            String priPort = null == app.getAppPriPort() ? "0" : String.valueOf(app.getAppPriPort());
            conPort2.setPriPort(priPort);
            String conip = command.substring(command.indexOf("--ip="), command.indexOf("-P ") - 1).replace("--ip=", "");
            conHostIp = releaseCore.appConInspect(host.getHostIp(), cluster.getClusterPort(), host.getHostUser(), host.getHostPwd(), con.getId());
            hostId = getHostId(conHostIp);
            container.setHostId(hostId);
            conPort2.setConIp(StringUtils.hasText(conip) ? conip : null);
        } else {
            if (con.getPorts().length > 0) {
                conHostIp = ports[0].getIp();
                hostId = getHostId(conHostIp);
                container.setHostId(hostId);
                // 获取服务端口,只保留应用服务端口
                App app = getAppInfo(0, model.getAppId());
                for (ContainerPort port : ports) {
                    if (app.getAppPriPort().equals(port.getPrivatePort())) {
                        container.setJmxPort(String.valueOf(port.getPublicPort()));
                        conPort = port;
                        break;
                    }
                }
            }
        }
        try {
            containerService.addContaier(container);
            if (null != model.getNetDriver() && 5 == model.getNetDriver()) {
                try {
                    conPort2.setContainerId(container.getConId());
                    conPort2.setPubPort("0");
                    conportService.addConports(conPort2);
                } catch (Exception e) {
                    LOGGER.error("Mofify container port infos failed");
                    return -1;
                }
            } else if (conPort != null) {
                addConPort(container.getConId(), conPort);
            }
        } catch (Exception e) {
            LOGGER.error("Create container error", e);
            return -1;
        }
        List<HostResourceModel> hrmList = model.getHrmList();
        /** 对于hrmList进行判空处理,HostResourceModel */
        if (hrmList != null && hostId != 0) {
            if (cluster.getResType() == Type.CLUSTER_RES.PRIVATE.ordinal()) {
                String cpuComand = command.substring(command.indexOf("-cpus="), command.indexOf("-m ") - 1);
                String[] cpus = cpuComand.split("=")[1].split(",");
                Integer[] cpuIds = new Integer[cpus.length];
                for (int i = 0; i < cpus.length; i++) {
                    cpuIds[i] = Integer.parseInt(cpus[i]);
                }
                for (HostResourceModel hrm : hrmList) {
                    //判断本次更新容器
                    if (hrm.getHostId() == hostId && Arrays.equals(cpuIds, hrm.getHostCpuCore())) {
                        crService.updateConIdByHostIdAndCpuIds(hostId, cpuIds, container.getConId());
                        //如果启动时创建了macvlan
                        if (null != hrm.getIpPoolId()) {
                            IpPool ipPool = new IpPool();
                            ipPool.setId(hrm.getIpPoolId());
                            ipPool.setConId(container.getConId());
                            try {
                                ipPoolService.changeToUnfree(ipPool);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            } else {
                for (HostResourceModel hrm : hrmList) {
                    //如果启动时创建了macvlan
                    if (null != hrm.getMacVlanIp() && command.contains(hrm.getMacVlanIp()) && null != hrm.getIpPoolId()) {
                        IpPool ipPool = new IpPool();
                        ipPool.setId(hrm.getIpPoolId());
                        ipPool.setConId(container.getConId());
                        try {
                            ipPoolService.changeToUnfree(ipPool);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
        return container.getConId();
    }

    /**
     * @param conId
     * @param port
     * @return
     * @author langzi
     * @version 1.0 2015年11月25日
     */
    private int addConPort(Integer conId, ContainerPort port) {
        ConPort conPort = new ConPort();
        conPort.setContainerId(conId);
        String ip = port.getIp();
        conPort.setConIp(StringUtils.hasText(ip) ? ip : null);
        int publicPort = port.getPublicPort();
        int privatePort = port.getPrivatePort();
        if (publicPort > 0) {
            conPort.setPubPort(String.valueOf(publicPort));
        }
        if (privatePort > 0) {
            conPort.setPriPort(String.valueOf(privatePort));
        }
        try {
            return conportService.addConports(conPort);
        } catch (Exception e) {
            LOGGER.error("Mofify container port infos failed");
            return -1;
        }
    }

    private Integer getHostId(String hostIp) {
        Host host = new Host();
        host.setHostIp(hostIp);
        host.setHostType((byte) Type.HOST.DOCKER.ordinal());
        try {
            host = hostService.getHostByIp(host);
            return host.getHostId();
        } catch (Exception e) {
            LOGGER.error("Get host id by ip and type failed", e);
            return null;
        }
    }

    public void pushMessage(final Integer userId, final MessageResult message) {
        messagePush.pushMessage(userId, JSONObject.toJSONString(message));
        LOGGER.info("Send message :" + message + ", to user(id:" + userId + ")");
    }

    /**
     * @param imageId
     * @param replaceNum
     */
    @SuppressWarnings("unused")
    private void replaceApplication(int imageId, int replaceNum) {
        try {
            List<SimpleContainer> simList = containerService.selectContainerByImageId(imageId, replaceNum);
            String[] conids = new String[simList.size()];
            for (int i = 0; i < simList.size(); i++) {
                SimpleContainer sim = simList.get(i);
                conids[i] = String.valueOf(sim.getContainerId());
            }
            rollbackApplication(conids);
            LOGGER.debug("delete container[" + org.apache.commons.lang.StringUtils.join(conids) + "] by oldimageid["
                    + imageId + "]  success.");
        } catch (Exception e) {
            LOGGER.error("get container by imageid[" + imageId + "] error", e);
        }
    }

    private Result getReplacedContainer(int imageId, int replaceNum) {
        try {
            List<SimpleContainer> simList = containerService.selectContainerByImageId(imageId, replaceNum);
            String conids = "";
            for (int i = 0; i < simList.size(); i++) {
                SimpleContainer sim = simList.get(i);
                conids += String.valueOf(sim.getContainerId()) + (i == simList.size() - 1 ? "" : ",");
            }
            return new Result(true, conids);
        } catch (Exception e) {
            LOGGER.error("get container by imageid[" + imageId + "] error", e);
            return new Result(true, "");
        }
    }

    /**
     * @param containerIds
     */
    private void rollbackApplication(String[] containerIds) {
        Result result = null;
        result = containerManager.stopContainer(containerIds);
        if (result.isSuccess()) {
            result = containerManager.removeContainer(containerIds);
        }
    }

    private void addAppChangeLog(App app, Image image, Integer userId, String logAction, String result, String detail,
                                 String accessIp) {
        AppChangeLog appChangeLog = new AppChangeLog();
        appChangeLog.setLogAction(logAction);
        if (app != null) {
            appChangeLog.setLogAppName(app.getAppName());
            appChangeLog.setAppId(app.getAppId());
        }
        if (image != null) {
            appChangeLog.setLogAppTag(image.getImageTag());
            appChangeLog.setImgId(image.getImageId());
            appChangeLog.setLogDetail(image.getImageName() + image.getImageTag() + detail);
        }
        appChangeLog.setLogCreatetime(new Date());
        appChangeLog.setUserId(userId);
        appChangeLog.setLogResult(result);
        appChangeLog.setUserIp(accessIp);

        try {
            appChangeLogService.save(appChangeLog);
        } catch (Exception e) {
            LOGGER.error("添加应用变更记录异常！");
        }


    }

    public JSONArray checkEnableAppReleaseMaxNum(App app) {
        try {
            int appId = app.getAppId();
            int netId = app.getNetId();
            //如果是macvlan类型，判断ip池允许扩容的数量
            Net net = netService.getNetByNetId(netId);
            if (net.getNetDriver() == 5) {
                IpPool ippool = new IpPool();
                ippool.setMacvlanId(netId);
                List<IpPool> ippoolList = ipPoolService.selectAllFree(ippool);
                JSONArray ja = (JSONArray) JSONArray.toJSON(ippoolList);
                return ja;
            }
            List<App> appList = appService.selectAppByAppIdAndNetId(app);
            if (null != appList) {
                if (appList.size() == 0) {
                    // int enableExtendMaxNum = 253;
                    JSONArray ja = (JSONArray) JSONArray.toJSON(appList);
                    return ja;
                } else {

                    ConNet conNet = new ConNet();
                    conNet.setAppId(appId);
                    conNet.setNetId(netId);

                    List<ConNet> conNetList = netService.selectConNetByAppIdAndNetId(conNet);
                    // int enableExtendMaxNum = 253 - conNetList.size();
                    JSONArray ja = (JSONArray) JSONArray.toJSON(conNetList);
                    return ja;
                }
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * @author yangqinglin
     * @description 添加软件方式部署
     */
    public Result appDeploy(ApplicationReleaseModel ARModel) {
		/* (0)获取请求的参数信息 */
		/* 获取发布容器的目标环境的ID */
        int targetEnvId = ARModel.getEnvId();
        int userId = ARModel.getUserId();
		/* 获取目标集群的ID */
        int targetClusterId = ARModel.getClusterId();

		/* (1)校验并获取集群信息 */
        Cluster cluster = getClusterInfo(targetClusterId);
        if (null == cluster) {
            return new Result(false, "未获取应用部署的目标集群信息！");
        }
        pushMessage(userId, new MessageResult(false, "10#" + "获取集群信息(名称：" + cluster.getClusterName() + ")成功。", "应用发布"));

		/* (4)获取部署的应用信息 */
        Integer targetAppId = ARModel.getAppId();
        App app = getAppInfo(ARModel.getTenantId(), ARModel.getAppId());
        if (null == app) {
            return new Result(false, "应用发布失败：未获取到应用信息");
        }
		/* 将App转化为ApplicationModel类数据 */
        ApplicationModel appModel = new ApplicationModel();
        BeanUtils.copyProperties(app, appModel);
        pushMessage(userId, new MessageResult(false, "40#" + "获取应用信息(名称:" + app.getAppName() + ")成功。", "应用发布"));

		/* （6）根据发布方式获取应用的默认属性信息 */
        if (null != app.getAppEnv()) {
            ARModel.setEnv(app.getAppEnv());
        }
        if (null != app.getAppVolumn()) {
            ARModel.setVolume(app.getAppVolumn());
        }
        if (null != app.getAppParams()) {
            ARModel.setParams(app.getAppParams().replaceAll(";", " "));
        }

		/* 获取并保存待发布容器的数量 */
        int conReleaseNum = ARModel.getReleaseNum();
        List<HostModel> hostList = new ArrayList<HostModel>();
        try {
            List<Host> rawHostList = hostService.listIdleAscHost(targetClusterId, targetAppId);
            int hostSize = rawHostList.size();
            if (hostSize < conReleaseNum) {
                return new Result(false, "可用主机数量(" + hostSize + ")不足以需求部署两(" + conReleaseNum + ")，请核对部署数量");
            } else {
                for (int count = 0; count < conReleaseNum; count++) {
                    HostModel newHm = new HostModel();
                    Host curHost = rawHostList.get(count);
                    BeanUtils.copyProperties(curHost, newHm);
                    hostList.add(newHm);
                }
            }
        } catch (Exception e3) {
            return new Result(false, "通过集群(集群ID:" + targetClusterId + ")获取主机链表失败！");
        }

		/* 根据请求的负载集群ID获取当前是否调用负载均衡 */
        @SuppressWarnings("unused")
        boolean userLoadBalance = false;
        if (ARModel.getBalanceId() != 0) {
            userLoadBalance = true;
        }

		/* (9)根据容器发布的数量，向数据库插入对应条数相同的UUID空白记录 */
        String UuidFlag = UUID.randomUUID().toString();
        List<Container> preSetConList = new ArrayList<Container>();
        for (int preCount = 0; preCount < conReleaseNum; preCount++) {
            Container preSetContainer = new Container();
            preSetContainer.setConUuid(UuidFlag);
            preSetConList.add(preSetContainer);
        }
		/* 批量插入预置空容器数据 */
        try {
            containerService.batchInsertPresetCons(preSetConList);
        } catch (Exception e2) {
            LOGGER.info("Batch Insert Presrt Containers Exception Failed!", e2);
            return new Result(false, "批量向容器中插入预置数据失败，请检查数据库连接！");
        }

		/* 查询去拿不数据标志位的容器列表，作为待发布容器的预置ID */
        CopyOnWriteArrayList<Integer> preSetConIdList = new CopyOnWriteArrayList<Integer>();
        try {
            List<Container> sameUuidFlagConList = containerService.selectSameUuidFlagCons(UuidFlag);
			/* 遍历相同UUID的容器链表，填充容器ID信息 */
            for (Container sameUuidFlagCon : sameUuidFlagConList) {
                preSetConIdList.add(sameUuidFlagCon.getConId());
            }
			/* 在ARModel填充容器ID列表 */
            ARModel.setPreSetConIdList(preSetConIdList);
        } catch (Exception e1) {
            LOGGER.info("Select Same Uuid(" + UuidFlag + ") Containers List Exception Failed!", e1);
            return new Result(false, "从数据库中查询相同UuidFlag(" + UuidFlag + ")空白实力列表失败，请检查数据库！");
        }

		/* 通过环境的Id查询获取环境相关的信息，并转化为EnvModel类型 */
        EnvModel envModel = new EnvModel();
        try {
            Env env = envService.find(targetEnvId);
            if (null != env) {
                pushMessage(userId, new MessageResult(false, "50#" + "获取环境信息(" + env.getEnvName() + ")成功。", "应用发布"));
                BeanUtils.copyProperties(env, envModel);
            } else {
                return new Result(false, "应用发布失败：未获取环境(ID:" + targetEnvId + ")信息！");
            }
        } catch (Exception e5) {
            LOGGER.error("Get Env by Id(" + targetEnvId + ") Failed!", e5);
        }

		/* 获取软件版本的信息 */
        ImageModel imgModel = new ImageModel();
        Integer imageId = ARModel.getImageId();
        try {
            Image img = imgService.loadImage(NormalConstant.ADMIN_TENANTID, imageId);
            if (null != img) {
                pushMessage(userId, new MessageResult(false,
                        "50#" + "获取镜像信息(" + img.getImageName() + ":" + img.getImageTag() + ")成功。", "应用发布"));
                BeanUtils.copyProperties(img, imgModel);
            } else {
                return new Result(false, "应用发布失败：未获取镜像(ID:" + imageId + ")信息！");
            }
        } catch (Exception e6) {
            LOGGER.error("Get Soft Info By Id(" + imageId + ") Failed!", e6);
            return new Result(false, "应用发布失败：未获取镜像(ID:" + imageId + ")信息！");
        }

		/* Line786：获取仓库主机和端口等信息，组装http下载路径 */
        String appPkgFolder = "";
        String subFolder = systemConfig.getSoftRegiPath();
        try {
            Registry registry = regiService.getByImage(imageId);
			/* 获取仓库所在主机的IP地址 */
            Integer regiHostId = registry.getHostId();
            Host regiHost = hostService.loadHost(regiHostId);
			/* 获取仓库使用的端口 */
            appPkgFolder = "http://" + regiHost.getHostIp() + ":" + registry.getRegistryPort() + "/" + subFolder;
			/* 将wget请求路径写入imageModel对象中 */
            imgModel.setAppPkgFolder(appPkgFolder);
        } catch (Exception e7) {
            LOGGER.error("Get Registry Info By imageId(" + imageId + ") Failed!", e7);
            return new Result(false, "应用发布失败：为获取软件(ID:" + imageId + "所属的仓库信息)！");
        }

		/* (10)开始部署软件的实例 */
        JSONArray instances = releaseCore.deployApp(appModel, envModel, ARModel, hostList, imgModel);
        pushMessage(userId, new MessageResult(false, "70#" + "应用实例启动完成。", "应用发布"));

		/* 对应用发布结果进行处理 */
        ArrayList<String> failInfoList = new ArrayList<String>();// 保存容器启动的错误信息
        ArrayList<Integer> failBootConIdList = new ArrayList<Integer>();// 启动错误容器的预置ID列表
        ArrayList<ApplicationReleaseModel> appInstanceList = new ArrayList<ApplicationReleaseModel>();// 存放发布成功的实例
        int totalSuccConNum = 0;// 总启动成功容器数量
        String errorInfo = "";// 启动容器返回的错误信息
        String conIds = "";// 启动成功的容器Id

		/* 区分发布成功与失败的应用实例 */
        for (int conCount = 0, conSize = instances.size(); conCount < conSize; conCount++) {
            JSONObject conJo = instances.getJSONObject(conCount);
            if (conJo.getString("failInfo") == null) {
                JSONArray ports = (JSONArray) conJo.get("ports");
                ApplicationReleaseModel arm = new ApplicationReleaseModel();
                BeanUtils.copyProperties(ARModel, arm);
                arm.setConJo(conJo);
                arm.setAppStatus(Status.APP_STATUS.UNDEFINED.ordinal());
                arm.setMonitorStatus(Status.MONITOR_STATUS.UNDEFINED.ordinal());
                appInstanceList.add(arm);
                conIds += insertDBInstanceInfo(arm, conJo, ports) + (conCount == (conSize - 1) ? "" : ",");
                LOGGER.error("Success Deploy Instance Ids(" + conIds + ") !");
                totalSuccConNum++;
            } else {
				/* 当实例启动失败后，JSONObject只有错误信息，实例ID等信息 */
                failInfoList.add(conJo.getString("failInfo"));
                failBootConIdList.add(conJo.getInteger("preSetConId"));
            }
        }

		/* 发布异常及失败应用实例处理，返回信息 */
        // if (failInfoList.size() > 0) {
        // errorInfo = statisticError(failInfoList);
		/* 批量删除全部启动错误的应用实例 */
        // batchDeleteFailCons(failBootConIdList);
        // }

		/* 发布陈宫的应用实例进行教案空和 */
        // int finalRemainNum = 0;// 记录应用实例最终成功个数
        // String checkMessage = "应用实例启动成功,";// 用户提示信息

        String retMessage = "应用启动了<font color=\"red\">" + totalSuccConNum + "</font>个.<br>" + errorInfo;
        pushMessage(userId, new MessageResult(false, "100#" + "应用发布完成", "应用发布"));

        //return new ConResult(true, retMessage, conIds);
        return new Result(true, retMessage);
    }

    private Integer insertDBInstanceInfo(ApplicationReleaseModel arm, JSONObject conJo, JSONArray ports) {

        Container instance = new Container();
        instance.setConId(conJo.getInteger("preSetConId"));
        instance.setConUuid(conJo.getString("ContainerId"));
        // 容器名称
        // container.setConName(model.getConName());
        instance.setConImgid(conJo.getInteger("imageId"));
        instance.setConPower((byte) conJo.getInteger("conPower").intValue());
        instance.setAppStatus((byte) conJo.getInteger("conStatus").intValue());
        instance.setConType((byte) Type.CONTAINER_TYPE.SOFTWARE.ordinal());
        // container.setMonitorStatus((byte) model.getMonitorStatus());
        instance.setConDesc(arm.getAppDesc());
        // container.setConMem(model.getMem());
        instance.setConStatus((byte) conJo.getInteger("conStatus").intValue());
        // container.setConStartCommand(command);
        // container.setConStartParam(model.getParams());
        instance.setAppId(conJo.getInteger("appId"));
        instance.setHostId(conJo.getInteger("hostId"));
        // container.setClusterIp(host.getHostIp());
        // container.setClusterPort(cluster.getClusterPort());
        instance.setConCreator(arm.getUserId());
        instance.setConCreatetime(new Date());
        // container.setMonitorHostId(model.getMonitorHostId());
        instance.setEnvId(conJo.getInteger("envId"));
        instance.setTenantId(arm.getTenantId());

        try {
            containerService.modifyContainer(instance);
            if ((ports != null) && (ports.size() != 0)) {
                for (int count = 0, size = ports.size(); count < size; count++) {
                    JSONObject json = (JSONObject) ports.get(count);
                    ConPort conPort = new ConPort();
                    conPort.setContainerId(conJo.getInteger("preSetConId"));
                    conPort.setConIp(json.getString("ip"));
                    conPort.setPriPort(json.getInteger("privatePort") + "");
                    conPort.setPubPort(json.getInteger("publicPort") + "");
                    conportService.addConports(conPort);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Create container_port  error", e);
        }
        return instance.getConId();
    }

    //集群调度
    public Result clusterSchedule(int appId, int oldClusterId, int newClusterId, Integer userId, String remoteHost) {
        /**
         * 集群调度将原来的应用部署集群替换为目标集群
         * 成功后当发生扩容时应用会将新的应用发布到目标集群
         */
        ClusterApp record = new ClusterApp();
        record.setAppId(appId);
        record.setClusterId(newClusterId);
        try {
            int i = caService.addClusterApp(record);
            if (i == 0) {
                return new Result(false, "集群调度更新失败！");
            }
        } catch (Exception e) {
            return new Result(false, "集群调度更新异常：" + e.getCause().getMessage());
        }
        try {
            List<ClusterApp> clusterApps = caService.listClusterAppsByAppIdAndClusterId(appId, oldClusterId);
            for (ClusterApp clusterApp : clusterApps) {
                caService.deleteByID(clusterApp.getId());
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new Result(false, "集群调度更新异常：" + e.getCause().getMessage());
        }
        return new Result(true, "集群调度更新成功");
    }

    /**
     * 灰度发布添加日志
     *
     * @author DZG
     * @since 2017年3月23日
     */
    private void addGrayLog(int appId, int imgId, int envId, int tenantId, int userId, String logAction, String result,
                            String detail, String accessIp) {
        Image image = null;
        App app = getAppInfo(tenantId, appId);
        try {
            image = imgService.loadImage(null, imgId);
            addAppChangeLog(app, image, userId, logAction, result, detail, accessIp);
        } catch (Exception e) {
            LOGGER.error("添加应用变更记录异常！");
        }
    }

    //*****************记录容器状态日志    2017-03-17  by   jingziming   end  *************//

    /**
     * 通过镜像部署参数查询 灰度任务的方法
     *
     * @author DZG
     * @since 2017年3月24日
     */
    public AppGrayReleaseEntity getOne(ApplicationReleaseModel model) {
        try {
            AppGrayReleaseEntity are = appGrayReleaseService.getOne(model.getAppId(), model.getEnvId(),
                    model.getImageId(), model.getUserId(), model.getTenantId(), 1);
            return are;
        } catch (SqlException e) {
            LOGGER.error("查询灰度任务异常！", e);
        }
        return null;
    }

    /**
     * 删除灰度发布任务记录的方法
     *
     * @author DZG
     * @since 2017年3月24日
     */
    public int deleteGrayRel(int id) {
        try {
            return appGrayReleaseService.delete(id);
        } catch (SqlException e) {
            LOGGER.error("删除灰度任务异常！", e);
        }
        return 0;
    }

    /**
     * 修改灰度发布参数
     *
     * @author DZG
     * @since 2017年3月20日
     */
    public Result modifyGrayRel(int appId, int envId, int imgId, int userId, int tenantId, int stepSize,
                                int intervalTime, int timeLevel) {
        int intervalTimeEdit = 0;
        AppGrayReleaseEntity are = new AppGrayReleaseEntity();
        try {
            are = appGrayReleaseService.getOne(appId, envId, imgId, userId, tenantId, 1);
            if (are == null) {
                return new Result(false, "该应用没有进行灰度滚动发布！");
            }
            if (are.getNewConNum() <= 0) {
                return new Result(false, "该灰度发布已经发布完成！");
            }
            if (are.getAutoExecute() != 0) {
                return new Result(false, "请先将该灰度发布停止！");
            }
            // 因为定时任务 的时间间隔从创建时就固定
            // 所以 修改时间间隔需要重新 生成定时任务
            // 1. 先将原灰度记录 删除
            AppGrayReleaseEntity newEntity = new AppGrayReleaseEntity();
            BeanUtils.copyProperties(are, newEntity);
            if (appGrayReleaseService.delete(are.getId()) > 0) {
                // 2. 删除成功，在重新插入新的 灰度数据，等待启动
                newEntity.setStepSize(stepSize);
                switch (timeLevel) {
                    case 0:
                        intervalTimeEdit = intervalTime;
                        break;
                    case 1:
                        intervalTimeEdit = intervalTime * 60;
                        break;
                    case 2:
                        intervalTimeEdit = intervalTime * 60 * 24;
                        break;
                    default:
                        intervalTimeEdit = intervalTime;
                        break;
                }
                newEntity.setIntervalTime(intervalTimeEdit);
                if (appGrayReleaseService.create(newEntity) > 0) {
                    addGrayLog(appId, imgId, envId, tenantId, userId, "修改灰度发布", "success", "修改成功",
                            newEntity.getAccessIp());
                    return new Result(true, "修改灰度发布成功");
                }
            }
        } catch (SqlException e) {
            addGrayLog(appId, imgId, envId, tenantId, userId, "修改灰度发布", "error", "修改失败", are.getAccessIp());
            return new Result(false, "修改灰度发布异常：" + e.getCause().getMessage());
        }
        return new Result(true, "修改灰度发布成功");
    }

    /**
     * 启动灰度滚动发布
     *
     * @author DZG
     * @since 2017年3月20日
     */
    public Result startGrayRel(int appId, int envId, int imgId, int userId, int tenantId) {
        AppGrayReleaseEntity are = new AppGrayReleaseEntity();
        try {
            are = appGrayReleaseService.getOne(appId, envId, imgId, userId, tenantId, 1);
            if (are == null) {
                return new Result(false, "该应用没有进行灰度滚动发布！");
            }
            if (are.getNewConNum() <= 0) {
                return new Result(false, "该灰度发布已经发布完成！");
            }
            if (are.getAutoExecute() > 0) {
                return new Result(false, "该灰度发布正在运行！");
            }
            if (are.getStepSize() == 0 || are.getIntervalTime() == 0) {
                return new Result(false, "请先修改灰度任务(步长和时间间隔)！");
            }
            if (are.getAutoExecute() == 0 && are.getNewConNum() > 0) {
                // 先修改 AUTO_EXECUTE 为 1 表示启动
                AppGrayReleaseEntity areStart = new AppGrayReleaseEntity(are.getId(), are.getStepSize(),
                        are.getIntervalTime(), 1, are.getNewConNum(), 1);
                appGrayReleaseService.update(areStart);
                // 重新启动
                ApplicationReleaseModel model = new ApplicationReleaseModel();
                model.setAppId(appId);
                model.setImageId(imgId);
                model.setOldImageId(are.getOldImageId());
                model.setEnvId(envId);
                model.setUserId(userId);
                model.setTenantId(tenantId);
                if (!createGrayReleaseTask(are, model).isSuccess()) {
                    return new Result(false, "该灰度发布已经完成，不需要重新启动！");
                }
            } else {
                return new Result(false, "该灰度发布已经完成，不需要重新启动！");
            }

        } catch (SqlException e) {
            addGrayLog(appId, imgId, envId, tenantId, userId, "启动灰度发布", "error", "启动失败",
                    are.getAccessIp());
            return new Result(false, "启动灰度发布异常：" + e.getCause().getMessage());
        }
        addGrayLog(appId, imgId, envId, tenantId, userId, "启动灰度发布", "success", "启动成功",
                are.getAccessIp());
        return new Result(true, "启动灰度发布成功");
    }

    /**
     * 停止灰度滚动发布
     *
     * @author DZG
     * @since 2017年3月20日
     */
    public Result cancelGrayRel(int appId, int envId, int imgId, int userId, int tenantId) {
        AppGrayReleaseEntity are = new AppGrayReleaseEntity();
        try {
            are = appGrayReleaseService.getOne(appId, envId, imgId, userId, tenantId, 1);
            if (are == null) {
                return new Result(false, "该应用没有进行灰度滚动发布！");
            }
            if (are.getNewConNum() <= 0) {
                return new Result(false, "该灰度发布已经发布完成！");
            }
            if (are.getAutoExecute() == 0) {
                return new Result(false, "该灰度发布已经停止！");
            }
            are.setAutoExecute(0);
            are.setStatus(1);
            if (appGrayReleaseService.update(are) > 0) {
                addGrayLog(appId, imgId, envId, tenantId, userId, "停止灰度发布", "success", "停止成功",
                        are.getAccessIp());
                return new Result(true, "停止灰度发布成功");
            }
        } catch (SqlException e) {
            addGrayLog(appId, imgId, envId, tenantId, userId, "停止灰度发布", "error", "停止失败",
                    are.getAccessIp());
            return new Result(false, "停止灰度发布异常：" + e.getCause().getMessage());
        }
        return new Result(true, "停止灰度发布成功");
    }

    /**
     * 提前完整发布
     *
     * @author DZG
     * @since 2017年3月20日
     * 修改人：mayh
     * 时间：20170321
     * 原因：添加扩容容器日志说明
     */
    public Result completeGrayRel(int appId, int envId, int imgId, int userId, int tenantId) {
        //1、灰度完整发布，第一步先把灰度任务停掉
        Result result = this.cancelGrayRel(appId, envId, imgId, userId, tenantId);
        if (result.isSuccess()) {
            LOGGER.info("灰度发布执行完整发布:停止自动滚动发布任务成功。");
        } else {
            return result;
        }
        //
        AppGrayReleaseEntity are = new AppGrayReleaseEntity();
        try {
            //2、暂停十秒钟，避免还有滚动发布任务在执行，会导致扩容出来的实例越界
            LOGGER.info("灰度发布执行完整发布:开始。");
            Thread.sleep(1000 * 5);
            are = appGrayReleaseService.getOne(appId, envId, imgId, userId, tenantId, 1);
            if (are == null) {
                return new Result(false, "该应用没有进行灰度滚动发布！");
            }
            if (are.getNewConNum() <= 0) {
                return new Result(false, "该灰度发布已经发布完成！");
            }
            /**
             * 3、判断是否有流量控制，如果有流量控制，需要先将流量控制置空，因为在完整发布时，缩容操作之前会更新负载，导致负载中会有流量控制。
             * 先将IP置空，发布失败再把IP填写回去。
             */
            if (StringUtils.hasText(are.getControlIp())) {
                Image image = imgService.loadImage(imgId);
                if (null != image) {
                    image.setGrayreleaseIp("");
                    imgService.update(image);
                }
            }

            int releaseNum = are.getNewConNum();
            are.setAutoExecute(0);
            are.setNewConNum(0);
            are.setStatus(0);
            if (appGrayReleaseService.update(are) > 0) {
                // 将记录删除
                deleteGrayRel(are.getId());
                // 开始将 剩余的 灰度发布一次性完成
                // 直接进行扩容
                if (appExtend(appId, are.getNewImageId(), envId, null, releaseNum, userId, tenantId, are.getAccessIp(), "灰度发布执行完整发布创建容器")
                        .isSuccess()) {
                    LOGGER.info("灰度发布执行完整发布:扩容成功。");
                    // 对应的缩容
                    if (!appReduce(appId, are.getOldImageId(), envId, null, releaseNum, userId, tenantId,
                            are.getAccessIp(), "灰度发布执行完整发布删除老版本").isSuccess()) {
                        addGrayLog(appId, imgId, envId, tenantId, userId, "完整发布灰度发布", "error", "完整发布失败",
                                are.getAccessIp());
                        LOGGER.info("灰度发布执行完整发布:缩容失败。");
                        return new Result(false, "完整发布灰度滚动任务失败！");
                    } else {
                        LOGGER.info("灰度发布执行完整发布:缩容成功。");
                    }
                } else {
                    addGrayLog(appId, imgId, envId, tenantId, userId, "完整发布灰度发布", "error", "完整发布失败",
                            are.getAccessIp());
                    LOGGER.info("灰度发布执行完整发布:扩容失败。");
                    return new Result(false, "完整发布灰度滚动任务失败！");
                }
                // 将Image表中 GRAYRELEASE_TYPE 和 GRAYRELEASE_IP 置空
                Image image = imgService.loadImage(null, imgId);
                image.setGrayreleaseType((byte) 0);
                image.setGrayreleaseIp("");
                imgService.update(image);
            }
        } catch (Exception e) {
            addGrayLog(appId, imgId, envId, tenantId, userId, "完整发布灰度发布", "error", "完整发布失败",
                    are.getAccessIp());
            return new Result(false, "完整发布灰度滚动任务异常：" + e.getCause().getMessage());
        }
        addGrayLog(appId, imgId, envId, tenantId, userId, "完整发布灰度发布", "success", "完整发布成功",
                are.getAccessIp());
        return new Result(true, "完整发布灰度滚动任务成功");
    }

    /**
     * 回滚发布
     *
     * @author DZG
     * @since 2017年3月21日
     * 修改人：mayh
     * 时间：20170321
     * 原因：添加扩容容器日志说明
     */
    public Result rollbackGrayRel(int appId, int envId, int imgId, int userId, int tenantId) {
        AppGrayReleaseEntity are = new AppGrayReleaseEntity();
        try {
            are = appGrayReleaseService.getOne(appId, envId, imgId, userId, tenantId, 1);
            if (are == null) {
                return new Result(false, "该应用没有进行灰度滚动发布！");
            }
            int number = are.getTotalConNum() - are.getNewConNum();
            are.setAutoExecute(0);
            are.setNewConNum(0);
            are.setStatus(0);
            if (appGrayReleaseService.update(are) > 0) {
                // 将记录删除
                deleteGrayRel(are.getId());
                // 开始将 剩余的 灰度发布一次性完成
                // 直接进行扩容
                if (appExtend(appId, are.getOldImageId(), envId, null, number, userId, tenantId, are.getAccessIp(), "灰度回滚发布创建容器")
                        .isSuccess()) {
                    // 对应的缩容
                    if (!appReduce(appId, are.getNewImageId(), envId, null, number, userId, tenantId,
                            are.getAccessIp(), "灰度回滚发布删除新版本").isSuccess()) {
                        addGrayLog(appId, imgId, envId, tenantId, userId, "回滚灰度发布", "error", "回滚失败",
                                are.getAccessIp());
                        return new Result(false, "回滚发布灰度滚动任务失败！");
                    }
                } else {
                    addGrayLog(appId, imgId, envId, tenantId, userId, "回滚灰度发布", "error", "回滚失败",
                            are.getAccessIp());
                    return new Result(false, "回滚发布灰度滚动任务失败！");
                }
                // 将Image表中 GRAYRELEASE_TYPE 和 GRAYRELEASE_IP 置空
                Image image = imgService.loadImage(null, imgId);
                image.setGrayreleaseType((byte) 0);
                image.setGrayreleaseIp("");
                imgService.update(image);
            }
        } catch (Exception e) {
            addGrayLog(appId, imgId, envId, tenantId, userId, "回滚灰度发布", "error", "回滚失败",
                    are.getAccessIp());
            return new Result(false, "回滚发布灰度滚动任务异常：" + e.getCause().getMessage());
        }
        addGrayLog(appId, imgId, envId, tenantId, userId, "回滚灰度发布", "success", "回滚成功",
                are.getAccessIp());
        return new Result(true, "回滚发布灰度滚动任务成功");
    }
    
    /**
     * 检测集群状态
     * 
     * @since 2017年5月5日
     */
	public Result testCluster(ApplicationReleaseModel model) {
		Image image = null;
		String accessIp = model.getAccessIp();
		// 获取应用信息
		App app = getAppInfo(model.getTenantId(), model.getAppId());
		int userId = model.getUserId();
		try {
			image = imgService.loadImage(null, model.getImageId());
		} catch (Exception e1) {
			LOGGER.error("获取镜像信息失败！");
			addAppChangeLog(app, image, userId, "应用发布", "error", "获取镜像信息失败", accessIp);
			return new Result(false, "获取镜像信息失败！");
		}
		// 防止dop_cluster_resource表数据有占用资源，暂时将所有容器id为0的记录中容器至为null
		try {
			crService.collbackUpdate();
		} catch (Exception e) {
			LOGGER.error("collback update clusterResouce containerId failed", e);
			addAppChangeLog(app, image, userId, "应用发布", "error", "集群资源回滚异常", accessIp);
			return new Result(false, "集群资源回滚异常！");
		}
		// 1.获取集群信息
		Cluster cluster = getClusterInfo(model.getClusterId());
		if (cluster == null) {
			LOGGER.error("获取应用集群信息异常！");
			addAppChangeLog(app, image, userId, "应用发布", "error", "获取应用集群信息失败", accessIp);
			return new Result(false, "未获取应用集群信息!");
		}
		Container con = new Container();
		con.setClusterPort(cluster.getClusterPort());
		pushMessage(userId, new MessageResult(false, "10#" + "获取集群信息(名称:" + cluster.getClusterName() + ")成功。", "应用发布"));
		// 2.检查集群是否正常
		Result result = clusterManager.clusterHealthCheck(cluster.getClusterId());
		if (!result.isSuccess()) {
			LOGGER.error("应用集群状态异常！");
			addAppChangeLog(app, image, userId, "应用发布", "error", "应用集群状态异常", accessIp);
			return new Result(false, "应用集群状态异常，请检查集群状态，再发布应用！");
		}
		pushMessage(userId, new MessageResult(false, "15#" + "集群健康检查(名称:" + cluster.getClusterName() + ")成功。", "应用发布"));
		// 3.获取集群所在主机信息
		Host host = getHostInfo(cluster.getMasteHostId());
		if (host == null) {
			LOGGER.error("获取集群所在主机信息失败！");
			addAppChangeLog(app, image, userId, "应用发布", "error", "获取集群所在主机信息失败", accessIp);
			return new Result(false, "未获取集群所在主机信息！");
		}
		// 判断集群下是否有子节点
		try {
			List<Host> slaveHost = hostService.listHostByClusterId(cluster.getClusterId());
			if (slaveHost.isEmpty()) {
				LOGGER.error("集群中不存在可使用的节点！");
				addAppChangeLog(app, image, userId, "应用发布", "error", "集群中不存在可使用的节点", accessIp);
				return new Result(false, "集群中不存在可使用的节点，请先添加节点，再发布应用！");
			}
		} catch (Exception e) {
			LOGGER.error("Get slave node error", e);
			return new Result(false, "获取集群子节点信息异常");
		}
		pushMessage(userId,
				new MessageResult(false, "30#" + "获取集群节点信息(名称:" + cluster.getClusterName() + ")成功。", "应用发布"));
		return new Result(true, "");
	}
}
