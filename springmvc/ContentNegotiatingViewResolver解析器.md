http://blog.csdn.net/z69183787/article/details/41654603
##RESTful服务中很重要的一个特性即是同一资源,多种表述我们使用ContentNegotiatingViewResolver就可以做到，这个视图解析器允许你用同样的内容数据来呈现不同的view
如下面描述的三种方式:
 
1. 方式1  使用扩展名
http://www.test.com/user.xml    呈现xml文件
http://www.test.com/user.json    呈现json格式
http://www.test.com/user       使用默认view呈现，比如jsp等


2. 方式2  使用http request header的Accept
GET /user HTTP/1.1
Accept:application/xml
 
GET /user HTTP/1.1
Accept:application/json
 

 
3. 方式3  使用参数
http://www.test.com/user?format=xml
http://www.test.com/user?format=json
 
这三种方式各自的优缺点这里就不再介绍了
如何使用ContentNegotiatingViewResolver？
假设我们有这么一个目标：
/user/{userid}.json    用于返回一个描述User的JSON
/user/{userid}        用于返回一个展示User的JSP页面
/user/{userid}.xml     用于返回一个展示User的XML文件

配置文件说明 ：
<property name="order" value="1"></property>这里是解析器的执行顺序，如果有多个的话
<property name="defaultContentType" value="text/html" />如果所有的mediaType都没匹配上，就会使用defaultContentType
这里是是否启用扩展名支持，默认就是true
例如  /user/{userid}.json
<property name="favorPathExtension" value="true"></property>
这里是是否启用参数支持，默认就是true
例如  /user/{userid}?format=json
<property name="favorParameter" value="false"></property>
这里是否忽略掉accept header，默认就是false
例如     GET /user HTTP/1.1
Accept:application/json
<property name="ignoreAcceptHeader" value="true"></property>
 
我们的例子是采用.json , .xml结尾的,所以关掉两个

使用内容协商实现多视图例
根据前篇文件的介绍，这里直接给出例子;
ContentNegotiatingViewResolver是根据客户提交的MimeType(如 text/html,application/xml)来跟服务端的一组viewResover的MimeType相比较,如果符合,即返回viewResover的数据.
而 /user/123.xml, ContentNegotiatingViewResolver会首先将 .xml 根据mediaTypes属性将其转换成 application/xml,然后完成前面所说的比较.

配置xml
<context:component-scan base-package="com.controls" />
   
    <context:annotation-config />
   
    <bean class="org.springframework.web.servlet.view.ContentNegotiatingViewResolver">
        <property name="order" value="1" />
        <property name="favorParameter" value="false" />
        <property name="ignoreAcceptHeader" value="true" />
       
        <property name="mediaTypes">
            <map>
                <entry key="json" value="application/json" />
                <entry key="xml" value="application/xml" />        
            </map>
        </property>
       
        <property name="defaultViews">
            <list>
                <bean class="org.springframework.web.servlet.view.json.MappingJacksonJsonView"></bean>
                <bean class="org.springframework.web.servlet.view.xml.MarshallingView">
                    <constructor-arg>
                        <bean class="org.springframework.oxm.jaxb.Jaxb2Marshaller">
                             <property name="classesToBeBound">
                                <list>
                                   <value>com.model.User</value>
                                </list>
                             </property>
                        </bean>
                    </constructor-arg>
                </bean>
            </list>
        </property>
    </bean>
    <!-- 上面没匹配到则会使用这个视图解析器 -->
    <bean class="org.springframework.web.servlet.view.InternalResourceViewResolver">
        <property name="order" value="2" />
        <property name="prefix" value="/WEB-INF/views/" />
        <property name="suffix" value=".jsp" />
        <property name="viewClass" value="org.springframework.web.servlet.view.JstlView" />
    </bean>
	

