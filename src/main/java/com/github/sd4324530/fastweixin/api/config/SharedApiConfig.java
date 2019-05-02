package com.github.sd4324530.fastweixin.api.config;

import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.jedis.lock.JedisLock;
import com.github.sd4324530.fastweixin.api.response.GetJsApiTicketResponse;
import com.github.sd4324530.fastweixin.api.response.GetTokenResponse;
import com.github.sd4324530.fastweixin.exception.WeixinException;
import com.github.sd4324530.fastweixin.util.JSONUtil;
import com.github.sd4324530.fastweixin.util.NetWorkCenter;
import com.github.sd4324530.fastweixin.util.StrUtil;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * 继承ApiConfig，实现了多个节点共享公众号accessToken，jsApiTicket的需求
 * @author wutian
 */
public final class SharedApiConfig extends ApiConfig {

	private static final long serialVersionUID = 1L;

	private static final Logger LOG = LoggerFactory.getLogger(SharedApiConfig.class);
    
	private final JedisPool jedisPool;
	
	/**
	 * 公众号唯一标识，用于组装redis存储时所使用的key
	 */
	private final String mpId;
	
	private static final String ACCESS_TOKEN = "accessToken";
	private static final String JS_API_TICKET = "jsApiTicket";
	
	private String getKey(String filedName) {
		return "fastweixin:" + mpId + ":" + filedName;
	}
	
	private String getValueFromRedis(String filedName) {
		Jedis jedis = jedisPool.getResource();
		try {
			String value = jedis.get(getKey(filedName));
			return value;
		} finally {
			jedis.close();
		}
	}
	
	private void setValueToRedis(String filedName,String filedValue) {
		Jedis jedis = jedisPool.getResource();
		try {
			jedis.setex(getKey(filedName), 7100, filedValue);
		} finally {
			jedis.close();
		}
	}
	
	private void distributedSync(final LockCallback lockCallback) {
    	Jedis jedis = jedisPool.getResource();
    	try {
			JedisLock lock = new JedisLock(jedis, getKey("sync"),10 * 1000);
			boolean lockSuccess = false;
			try {
				lockSuccess = lock.acquire();
				if(lockSuccess) {
					lockCallback.doWithLock();
				}
			} catch (InterruptedException e) {
				LOG.error(e.getMessage(), e);
			} finally {
				if(lockSuccess) {
					lock.release();
				}
			}
    	} finally {
			jedis.close();
		}
	}
	
    /**
     * 构造方法一，实现同时获取access_token。不启用jsApi
     *
     * @param appid  公众号appid
     * @param secret 公众号secret
     * @param mpId 公众号唯一标识，用于组装redis存储时所使用的key
     * @param jedisPool jedisPool
     */
    public SharedApiConfig(String appid, String secret,String mpId,JedisPool jedisPool) {
        this(appid, secret, false, mpId, jedisPool);
    }

    /**
     * 构造方法二，实现同时获取access_token，启用jsApi
     *
     * @param appid       公众号appid
     * @param secret      公众号secret
     * @param enableJsApi 是否启动js api
     * @param mpId 公众号唯一标识，用于组装redis存储时所使用的key
     * @param jedisPool jedisPool
     */
    public SharedApiConfig(String appid, String secret, boolean enableJsApi,String mpId,JedisPool jedisPool) {
    	super(appid, secret, enableJsApi);
    	this.mpId = mpId;
	    this.jedisPool = jedisPool;
    }

    @Override
    public String getAccessToken() {
    	String accessToken = getValueFromRedis(ACCESS_TOKEN);
    	if(accessToken != null) {
    		return accessToken;
    	} else {
    		initToken(0l);
    		return getValueFromRedis(ACCESS_TOKEN);
    	}
    }
    
    @Override
    public String getJsApiTicket() {
    	String jsApiTicket = getValueFromRedis(JS_API_TICKET);
    	if(jsApiTicket != null) {
    		return jsApiTicket;
    	} else {
    		initJSToken(0l);
    		return getValueFromRedis(JS_API_TICKET);
    	}
    }
    
    @Override
    protected void initToken(final long refreshTime) {
    	if(jedisPool == null) {
    		return;
    	}
    	LOG.debug("开始初始化access_token........");
		distributedSync(new LockCallback() {
			@Override
			public void doWithLock() {
				if(getValueFromRedis(ACCESS_TOKEN) != null) {
					return;
				}
				
	            String url = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=" + getAppid() + "&secret=" + getSecret();
	            NetWorkCenter.get(url, null, new NetWorkCenter.ResponseCallback() {
	                @Override
	                public void onResponse(int resultCode, String resultJson) {
	                    if (HttpStatus.SC_OK == resultCode) {
	                        GetTokenResponse response = JSONUtil.toBean(resultJson, GetTokenResponse.class);
	                        LOG.debug("获取access_token:{}", response.getAccessToken());
	                        if (null == response.getAccessToken()) {
	                            throw new WeixinException("微信公众号token获取出错，错误信息:" + response.getErrcode() + "," + response.getErrmsg());
	                        }
	                        String newAccessToken = response.getAccessToken();
	                        setValueToRedis(ACCESS_TOKEN, newAccessToken);
	                        //设置通知点
	                        setChanged();
	                        notifyObservers(new ConfigChangeNotice(getAppid(), ChangeType.ACCESS_TOKEN, newAccessToken));
	                    }
	                }
	            });
			}
		});
    }

	@Override
	protected void initJSToken(long refreshTime) {
    	if(jedisPool == null) {
    		return;
    	}
        LOG.debug("初始化 jsapi_ticket........");
		distributedSync(new LockCallback() {
			@Override
			public void doWithLock() {
				if(getValueFromRedis(JS_API_TICKET) != null) {
					return;
				}
				
		        String url = "https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token=" + getAccessToken() + "&type=jsapi";
		        NetWorkCenter.get(url, null, new NetWorkCenter.ResponseCallback() {
		            @Override
		            public void onResponse(int resultCode, String resultJson) {
		                if (HttpStatus.SC_OK == resultCode) {
		                    GetJsApiTicketResponse response = JSONUtil.toBean(resultJson, GetJsApiTicketResponse.class);
		                    LOG.debug("获取jsapi_ticket:{}", response.getTicket());
		                    if (StrUtil.isBlank(response.getTicket())) {
		                        throw new WeixinException("微信公众号jsToken获取出错，错误信息:" + response.getErrcode() + "," + response.getErrmsg());
		                    }
		                    String newJsApiTicket = response.getTicket();
		                    setValueToRedis(JS_API_TICKET, newJsApiTicket);
		                    //设置通知点
		                    setChanged();
		                    notifyObservers(new ConfigChangeNotice(getAppid(), ChangeType.JS_TOKEN, newJsApiTicket));
		                }
		            }
		        });
			}
		});

	}
	
	private interface LockCallback {
		void doWithLock();
	}

}