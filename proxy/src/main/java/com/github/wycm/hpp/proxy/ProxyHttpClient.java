package com.github.wycm.hpp.proxy;

import com.alibaba.fastjson.JSON;
import com.github.wycm.hpp.http.util.HttpClientUtil;
import com.github.wycm.hpp.proxy.entity.Proxy;
import com.github.wycm.hpp.proxy.site.DefaultParserTemplate;
import com.github.wycm.hpp.proxy.site.UrlTemplate;
import com.github.wycm.hpp.proxy.task.ProxyPageTask;
import com.github.wycm.hpp.proxy.task.ProxySerializeTask;
import com.github.wycm.hpp.proxy.util.Config;
import com.github.wycm.hpp.http.util.SimpleThreadPoolExecutor;
import com.github.wycm.hpp.http.util.ThreadPoolMonitor;
import com.github.wycm.hpp.http.httpclient.AbstractHttpClient;
import com.github.wycm.hpp.proxy.util.ProxyUtil;
import org.apache.log4j.Logger;
import org.jsoup.helper.StringUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ProxyHttpClient extends AbstractHttpClient {
    private static final Logger logger = Logger.getLogger(ProxyHttpClient.class);
    private volatile static ProxyHttpClient instance;

    public static ProxyHttpClient getInstance(){
        if (instance == null){
            synchronized (ProxyHttpClient.class){
                if (instance == null){
                    instance = new ProxyHttpClient();
                }
            }
        }
        return instance;
    }
    /**
     * 代理测试线程池
     */
    private ThreadPoolExecutor proxyTestThreadExecutor;

    private Map<String, SimpleThreadPoolExecutor> threadPoolExecutorMap = new HashMap<>();

    private DefaultParserTemplate[] defaultParserTemplateArray = null;
    public ProxyHttpClient(){
        initThreadPool();
        try {
            initParser();
        } catch (IOException e) {
            e.printStackTrace();
        }
        initProxy();
    }
    /**
     * 初始化线程池
     */
    private void initThreadPool(){
        proxyTestThreadExecutor = new SimpleThreadPoolExecutor(300, 300,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(10000),
                new ThreadPoolExecutor.DiscardPolicy(),
                "proxyTestThreadExecutor");
        for(DefaultParserTemplate template : ProxyPool.defalutParserTemplateList){
            threadPoolExecutorMap.put(template.getDomain()
                    ,new SimpleThreadPoolExecutor(1, 2,
                            0L, TimeUnit.MILLISECONDS,
                            new LinkedBlockingQueue<Runnable>(1000), template.getDomain() +
                            "-ProxyDownloadThreadExecutor"));
        }
        new Thread(new ThreadPoolMonitor(proxyTestThreadExecutor, "ProxyTestThreadPool")).start();
    }

    /**
     * 初始化proxy
     */
    private void initProxy(){
        Proxy[] proxyArray = null;
        try {
            proxyArray = ProxyUtil.deserializeObject(Config.getProperty("proxyPath"));
            int usableProxyCount = 0;
            for (Proxy p : proxyArray){
                if (p == null){
                    continue;
                }
            }
            logger.info("反序列化proxy成功，" + proxyArray.length + "个代理,可用代理" + usableProxyCount + "个");
        } catch (Exception e) {
            logger.warn("反序列化proxy失败");
        }
    }
    private void initParser() throws IOException {
        File file = new File(Config.getProperty("defaultParserPath"));
        FileInputStream fis = null;
        fis = new FileInputStream(file);
        defaultParserTemplateArray = JSON.parseObject(fis, new DefaultParserTemplate[0].getClass());
        for(DefaultParserTemplate template : defaultParserTemplateArray){
            List<UrlTemplate> urlTemplateList = template.getUrlTemplateList();
            List<String> urlList = new LinkedList<>();
            for(UrlTemplate urlTemplate : urlTemplateList){
                List<String> classifyList = new ArrayList<>();
                if(!StringUtil.isBlank(urlTemplate.getClassifyList())){
                    classifyList.addAll(Arrays.asList(urlTemplate.getClassifyList().split("/")));
                }
                List<String> pageNumberRangeList = new ArrayList<>();
                if (!StringUtil.isBlank(urlTemplate.getPageNumberRangeList())){
                    pageNumberRangeList.addAll(Arrays.asList(urlTemplate.getPageNumberRangeList().split("/")));
                }
                parseClassify(urlList, classifyList, pageNumberRangeList, urlTemplate.getUrlTemplate(), 0);
            }
            template.setUrlList(urlList);
        }

    }
    private void parseClassify(List<String> urlList, List<String> classifyList, List<String> pageNumberRangeList, String url, int i){
        if (!url.contains("${classify}")){
            parsePageNumber(urlList, pageNumberRangeList, url, 0);
        }
        else {
            String[] classifyArray = classifyList.get(0).split(",");
            for(String s : classifyArray){
                String tempUrl = url.replaceFirst("\\$\\{classify\\}", s);
                parseClassify(urlList, classifyList, pageNumberRangeList, tempUrl, i + 1);
            }

        }


    }
    private void parsePageNumber(List<String> urlList, List<String> pageNumberRangeList, String url, int i){
        if (!url.contains("${pageNumber}")){
            urlList.add(url);
        }
        else {
            String[] pageNumberArray = pageNumberRangeList.get(i).split("-");
            int startPageNumber = Integer.valueOf(pageNumberArray[0]);
            int endPageNumber = Integer.valueOf(pageNumberArray[1]);
            for(int j = startPageNumber; j <= endPageNumber; j++){
                String pageNumberUrl = url.replaceFirst("\\$\\{pageNumber\\}", j + "");
                parsePageNumber(urlList, pageNumberRangeList, pageNumberUrl, i + 1);
            }
        }
    }
    /**
     * 抓取代理
     */
    public void startCrawl(){
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true){
                    for(DefaultParserTemplate template : ProxyPool.defalutParserTemplateList){
                        for(String url : template.getUrlList()) {
                            threadPoolExecutorMap.get(template.getDomain())
                                    .execute(new ProxyPageTask(template.getDomain(), url, false, template.getCharsetName()));
                        }
                    }
                    try {
                        Thread.sleep(1000 * 60 * 60);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
        new Thread(new ProxySerializeTask()).start();
    }
    public ThreadPoolExecutor getProxyTestThreadExecutor() {
        return proxyTestThreadExecutor;
    }

    public Map<String, SimpleThreadPoolExecutor> getThreadPoolExecutorMap() {
        return threadPoolExecutorMap;
    }
}