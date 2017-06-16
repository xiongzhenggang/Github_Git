# springmvc 自定义拦截器的实现
##SpringMVC的拦截器HandlerInterceptorAdapter对应提供了三个preHandle，postHandle，afterCompletion方法。
* preHandle在业务处理器处理请求之前被调用，postHandle在业务处理器处理请求执行完成后,生成视图之前执行
* afterCompletion在DispatcherServlet完全处理完请求后被调用,可用于清理资源等 。所以要想实现自己的权限管理逻辑，需要继承HandlerInterceptorAdapter并重写其三个方法。首先在springmvc.xml中加入自己定义的拦截器实现逻辑AccessInterceptor
xml中配置如下
<!-- mvc:interceptors  总拦截器， 拦截匹配的URL/**-->
···xml
<!--配置拦截器, 多个拦截器,顺序执行 -->  
<mvc:interceptors>    
    <mvc:interceptor>    
        <!-- 匹配的是url路径， 如果不配置或/**,将拦截所有的Controller -->  
        <mvc:mapping path="/" />  
        <mvc:mapping path="/user/**" />  
        <mvc:mapping path="/test/**" />  
        <bean class="com.alibaba.interceptor.CommonInterceptor">
		<property name="ignores">
					<list>
						<value>404</value>
						<value>login</value>
						<value>logout</value>
						<value>captcha</value>
					...
						
					</list>
				</property>
		</bean>    
    </mvc:interceptor>  
    <!-- 当设置多个拦截器时，先按顺序调用preHandle方法，然后逆序调用每个拦截器的postHandle和afterCompletion方法 -->  
</mvc:interceptors> 
···
拦截逻辑是“在未登录前，任何访问url都跳转到login页面；登录成功后跳转至先前的url”，具体代码

···java
/* 
     * 利用正则映射到需要拦截的路径     
      
    private String mappingURL; 
     
    public void setMappingURL(String mappingURL) {     
               this.mappingURL = mappingURL;     
    }    
  */  
    /**  
     * 在业务处理器处理请求之前被调用  
     * 如果返回false  
     *     从当前的拦截器往回执行所有拦截器的afterCompletion(),再退出拦截器链 
     * 如果返回true  
     *    执行下一个拦截器,直到所有的拦截器都执行完毕  
     *    再执行被拦截的Controller  
     *    然后进入拦截器链,  
     *    从最后一个拦截器往回执行所有的postHandle()  
     *    接着再从最后一个拦截器往回执行所有的afterCompletion()  
     */    
@Repository
public class AccessInterceptor implements HandlerInterceptor {

}@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		String requestPath = request.getServletPath();
        request.setCharacterEncoding("UTF-8");
		if (null != ignores) {
			for (String url : ignores) {
				//忽略的url
				if (requestPath.contains(url)) {
					return true;
				}
			}
		}
		User user = (User) request.getSession().getAttribute("user");
//检测会话是否到期
		if (null == user) {
			response.sendRedirect(request.getContextPath() + "/login.html");
			return false;
		}
		。。。
		}
 /** 
     * 在业务处理器处理请求执行完成后,生成视图之前执行的动作    
     * 可在modelAndView中加入数据，比如当前时间 
     */  
    @Override    
    public void postHandle(HttpServletRequest request,    
            HttpServletResponse response, Object handler,    
            ModelAndView modelAndView) throws Exception {     
        log.info("==============执行顺序: 2、postHandle================");    
        if(modelAndView != null){  //加入当前时间    
            modelAndView.addObject("var", "测试postHandle");    
        }    
    }    
	
···
