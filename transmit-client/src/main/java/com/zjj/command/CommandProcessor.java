package com.zjj.command;

import com.zjj.jrpc.config.spring.annotation.JRpcReference;
import com.zjj.netty.IpAddrHolder;
import com.zjj.netty.NettyClient;
import com.zjj.service.AddrCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import javax.annotation.Resource;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

@Slf4j
@Component
@EnableScheduling
public class CommandProcessor implements CommandLineRunner {

    @JRpcReference(protocol = "jrpc", registry = "zookeeper")
    private AddrCacheService addrCacheService;

    @Resource(name = "natThroughProcessor")
    private IpAddrHolder ipAddrHolder;

    @Resource(name = "udpClient")
    private NettyClient nettyClient;

    @Resource(name = "threadPoolTaskExecutor")
    private ThreadPoolTaskExecutor executor;

    @Override
    public void run(String... args) throws Exception {
        nettyClient.doBind();
        log.info("本机ID: {}", nettyClient.getLocalId());

        Set<String> oppositeIds = ipAddrHolder.getThroughIds();
        for (String oppositeId : oppositeIds) {
            executor.execute(() -> natTo(oppositeId));
        }
        if (args != null && args.length > 0 && "silent".equals(args[0])) {
            return;
        }
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String input = scanner.nextLine();
            String[] split = input.split(" +");
            if ("q!".equals(input)) {
                break;
            } else if ("nat".equals(split[0])) {
                String oppositeId = split[1].substring(1);
                executor.execute(() -> natTo(oppositeId));
            } else if ("though-map".equals(input)) {
                log.info("{}", ipAddrHolder.throughAddrMaps());
            } else if ("chat".equals(split[0])) {

            } else if ("heartT-map".equals(input)) {
                Field field = nettyClient.getClass().getDeclaredField("udpClientChannelHandler");
                field.setAccessible(true);
                Class<?> udpClientChannelHandler = field.getType();
                Field heartBeatThread = udpClientChannelHandler.getDeclaredField("HEART_BEAT_THREAD");
                heartBeatThread.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, String> threadMap = Collections.unmodifiableMap((Map<String, String>) heartBeatThread.get(udpClientChannelHandler));
                log.info("{}: {}", heartBeatThread.getName(), threadMap);
            } else if ("public-map".equals(input)) {
                Field publicAddr = ipAddrHolder.getClass().getDeclaredField("PUBLIC_ADDR_MAP");
                publicAddr.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, String> publicAddrMap = Collections.unmodifiableMap((Map<String, String>) publicAddr.get(ipAddrHolder));
                log.info("{}: {}", publicAddr.getName(), publicAddrMap);
            } else if ("private-map".equals(input)) {
                Field privateAddr = ipAddrHolder.getClass().getDeclaredField("PRIVATE_ADDR_MAP");
                privateAddr.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, String> privateAddrMap = Collections.unmodifiableMap((Map<String, String>) privateAddr.get(ipAddrHolder));
                log.info("{}: {}", privateAddr.getName(), privateAddrMap);
            }
        }


        addrCacheService.deletePrivateAddr(nettyClient.getLocalId());
        addrCacheService.deletePublicAddr(nettyClient.getLocalId());
        nettyClient.doClose();
    }


    private void natTo(String oppositeId) {
        long timeMillis = System.currentTimeMillis();
        String oppositePriAddr;
        try {
            oppositePriAddr = addrCacheService.getPrivateAddrStr(oppositeId);
        } catch (ResourceAccessException e) {
            log.error("服务器未响应...");
            return;
        }
        if (oppositePriAddr == null) {
            log.info("{} 未在线", oppositeId);
            return;
        }
        log.info("{} 的私网地址是 {}", oppositeId, oppositePriAddr);
        ipAddrHolder.setPriAddrStr(oppositeId, oppositePriAddr);
        log.debug("获取{}的私网地址用时{}ms", oppositeId, System.currentTimeMillis() - timeMillis);
        timeMillis = System.currentTimeMillis();
        String oppositePubAddr = addrCacheService.getPublicAddrStr(oppositeId);
        log.info("{} 的公网地址是 {}", oppositeId, oppositePubAddr);
        ipAddrHolder.setPubAddrStr(oppositeId, oppositePubAddr);
        log.debug("获取{}的公网地址用时{}ms", oppositeId, System.currentTimeMillis() - timeMillis);
        timeMillis = System.currentTimeMillis();
        log.debug("尝试与 {} 建立穿透", oppositeId);
        nettyClient.attemptNatConnect(oppositeId);
        log.debug("尝试与 {} 建立穿透用时 {}ms", oppositeId, System.currentTimeMillis() - timeMillis);
    }
}
