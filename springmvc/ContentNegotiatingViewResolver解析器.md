http://blog.csdn.net/z69183787/article/details/41654603
##RESTful�����к���Ҫ��һ�����Լ���ͬһ��Դ,���ֱ�������ʹ��ContentNegotiatingViewResolver�Ϳ��������������ͼ��������������ͬ�����������������ֲ�ͬ��view
���������������ַ�ʽ:
 
1. ��ʽ1  ʹ����չ��
http://www.test.com/user.xml    ����xml�ļ�
http://www.test.com/user.json    ����json��ʽ
http://www.test.com/user       ʹ��Ĭ��view���֣�����jsp��


2. ��ʽ2  ʹ��http request header��Accept
GET /user HTTP/1.1
Accept:application/xml
 
GET /user HTTP/1.1
Accept:application/json
 

 
3. ��ʽ3  ʹ�ò���
http://www.test.com/user?format=xml
http://www.test.com/user?format=json
 
�����ַ�ʽ���Ե���ȱ������Ͳ��ٽ�����
���ʹ��ContentNegotiatingViewResolver��
������������ôһ��Ŀ�꣺
/user/{userid}.json    ���ڷ���һ������User��JSON
/user/{userid}        ���ڷ���һ��չʾUser��JSPҳ��
/user/{userid}.xml     ���ڷ���һ��չʾUser��XML�ļ�

�����ļ�˵�� ��
<property name="order" value="1"></property>�����ǽ�������ִ��˳������ж���Ļ�
<property name="defaultContentType" value="text/html" />������е�mediaType��ûƥ���ϣ��ͻ�ʹ��defaultContentType
�������Ƿ�������չ��֧�֣�Ĭ�Ͼ���true
����  /user/{userid}.json
<property name="favorPathExtension" value="true"></property>
�������Ƿ����ò���֧�֣�Ĭ�Ͼ���true
����  /user/{userid}?format=json
<property name="favorParameter" value="false"></property>
�����Ƿ���Ե�accept header��Ĭ�Ͼ���false
����     GET /user HTTP/1.1
Accept:application/json
<property name="ignoreAcceptHeader" value="true"></property>
 
���ǵ������ǲ���.json , .xml��β��,���Թص�����

ʹ������Э��ʵ�ֶ���ͼ��
����ǰƪ�ļ��Ľ��ܣ�����ֱ�Ӹ�������;
ContentNegotiatingViewResolver�Ǹ��ݿͻ��ύ��MimeType(�� text/html,application/xml)��������˵�һ��viewResover��MimeType��Ƚ�,�������,������viewResover������.
�� /user/123.xml, ContentNegotiatingViewResolver�����Ƚ� .xml ����mediaTypes���Խ���ת���� application/xml,Ȼ�����ǰ����˵�ıȽ�.

����xml
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
    <!-- ����ûƥ�䵽���ʹ�������ͼ������ -->
    <bean class="org.springframework.web.servlet.view.InternalResourceViewResolver">
        <property name="order" value="2" />
        <property name="prefix" value="/WEB-INF/views/" />
        <property name="suffix" value=".jsp" />
        <property name="viewClass" value="org.springframework.web.servlet.view.JstlView" />
    </bean>
	

