# springmvc �Զ�����������ʵ��
##SpringMVC��������HandlerInterceptorAdapter��Ӧ�ṩ������preHandle��postHandle��afterCompletion������
* preHandle��ҵ��������������֮ǰ�����ã�postHandle��ҵ��������������ִ����ɺ�,������ͼ֮ǰִ��
* afterCompletion��DispatcherServlet��ȫ����������󱻵���,������������Դ�� ������Ҫ��ʵ���Լ���Ȩ�޹����߼�����Ҫ�̳�HandlerInterceptorAdapter����д������������������springmvc.xml�м����Լ������������ʵ���߼�AccessInterceptor
xml����������
<!-- mvc:interceptors  ���������� ����ƥ���URL/**-->
������xml
<!--����������, ���������,˳��ִ�� -->  
<mvc:interceptors>    
    <mvc:interceptor>    
        <!-- ƥ�����url·���� ��������û�/**,���������е�Controller -->  
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
    <!-- �����ö��������ʱ���Ȱ�˳�����preHandle������Ȼ���������ÿ����������postHandle��afterCompletion���� -->  
</mvc:interceptors> 
������
�����߼��ǡ���δ��¼ǰ���κη���url����ת��loginҳ�棻��¼�ɹ�����ת����ǰ��url�����������

������java
/* 
     * ��������ӳ�䵽��Ҫ���ص�·��     
      
    private String mappingURL; 
     
    public void setMappingURL(String mappingURL) {     
               this.mappingURL = mappingURL;     
    }    
  */  
    /**  
     * ��ҵ��������������֮ǰ������  
     * �������false  
     *     �ӵ�ǰ������������ִ��������������afterCompletion(),���˳��������� 
     * �������true  
     *    ִ����һ��������,ֱ�����е���������ִ�����  
     *    ��ִ�б����ص�Controller  
     *    Ȼ�������������,  
     *    �����һ������������ִ�����е�postHandle()  
     *    �����ٴ����һ������������ִ�����е�afterCompletion()  
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
				//���Ե�url
				if (requestPath.contains(url)) {
					return true;
				}
			}
		}
		User user = (User) request.getSession().getAttribute("user");
//���Ự�Ƿ���
		if (null == user) {
			response.sendRedirect(request.getContextPath() + "/login.html");
			return false;
		}
		������
		}
 /** 
     * ��ҵ��������������ִ����ɺ�,������ͼ֮ǰִ�еĶ���    
     * ����modelAndView�м������ݣ����統ǰʱ�� 
     */  
    @Override    
    public void postHandle(HttpServletRequest request,    
            HttpServletResponse response, Object handler,    
            ModelAndView modelAndView) throws Exception {     
        log.info("==============ִ��˳��: 2��postHandle================");    
        if(modelAndView != null){  //���뵱ǰʱ��    
            modelAndView.addObject("var", "����postHandle");    
        }    
    }    
	
������
