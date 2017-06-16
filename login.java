String basePath = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort()+ path + "/";

package com.bocloud.caas.controller.action;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.bocloud.caas.util.LoginUserCache;
import org.apache.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import com.bocloud.caas.component.CaptchaNumber;
import com.bocloud.caas.constant.Status;
import com.bocloud.caas.constant.UserStatusConstant;
import com.bocloud.caas.entity.Authority;
import com.bocloud.caas.entity.User;
import com.bocloud.caas.ldap.LdapAnon;
import com.bocloud.caas.manager.AuthorityManager;
import com.bocloud.caas.manager.UserManager; 
import com.bocloud.caas.model.LoginModel;
import com.bocloud.caas.service.UserService;
import com.bocloud.caas.util.CaptchaUtil;
import com.bocloud.caas.util.HashUtil;


@Controller
public class LoginAction {

	private static final Logger LOGGER = Logger.getLogger(LoginAction.class);
	@Resource
	private UserService userService;
	@Resource
	private UserManager userManager;
	@Resource
	private AuthorityManager authorityManager;
	
	@Resource
	private LdapAnon ldapAnon;

	@RequestMapping(value = "/login", method = { RequestMethod.POST })
	public ModelAndView login(HttpServletRequest request, HttpServletResponse response, LoginModel loginRequestModel,
			RedirectAttributes redirectAttributes) {
		LOGGER.info("【" + loginRequestModel.getUserName() + "】尝试登录");
		
		User user=new User();
		//使用addFlashAttribute,参数不会出现在url地址栏中  
		redirectAttributes.addFlashAttribute("userName", loginRequestModel.getUserName());
		redirectAttributes.addFlashAttribute("password", loginRequestModel.getPassword());
		// 判断验证码的session是否过期
		if (loginRequestModel.getVercode() == null || request.getSession().getAttribute("rand") == null) {
			LOGGER.warn("登录失败：验证码失效");
			redirectAttributes.addFlashAttribute("message", "验证码失效");
			return new ModelAndView(new RedirectView("/login.html"));
		} 
		//【二】：判断验证码是否正确
		String captcha = (String) request.getSession().getAttribute("rand");
		if (!loginRequestModel.getVercode().toLowerCase().equals(captcha.toLowerCase())) {
			LOGGER.error("登录失败：验证码不正确");
			redirectAttributes.addFlashAttribute("message", "验证码不正确！");
			return new ModelAndView(new RedirectView("/login.html"));
		} 
		
		int iResult = ldapAnon.authenricate(loginRequestModel.getUserName(), loginRequestModel.getPassword());
		if (iResult < 0 || (iResult != 0 && iResult!=1) ) {
			redirectAttributes.addFlashAttribute("message", "账号或密码错误！");
			return new ModelAndView(new RedirectView("/login.html"));
		}
		
		//根据用户ID获取用户信息 
		try {
			user = userService.getUserByUid(loginRequestModel.getUserName());
		} catch (Exception e) {
			LOGGER.error("check user login by userName["+loginRequestModel.getUserName()+"] falied!", e);
			redirectAttributes.addFlashAttribute("message", "用户登录失败，数据库连接异常！");
			return new ModelAndView(new RedirectView("/login.html"));
		}
		//用户不存在
		if (user == null) {
			redirectAttributes.addFlashAttribute("message", "用户不存在！");
			return new ModelAndView(new RedirectView("/login.html"));
		}
		//用户密码错误
		if(user != null && !StringUtils.hasText(user.getUserPass())){
			redirectAttributes.addFlashAttribute("message", "用户密码错误");
			return new ModelAndView(new RedirectView("/login.html"));
		}
		//如果ldap认证通过（1 == iResult），数据库密码和ldap密码不一致,则更新数据库密码 
		if(1 == iResult && !HashUtil.md5Hash(loginRequestModel.getPassword()).equals(user.getUserPass())) {
		    user.setUserPass(HashUtil.md5Hash(loginRequestModel.getPassword()));
		    try {
				userService.update(user);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		//如果未开启ldap认证，需要判断用户密码是否与数据库一致
		}else if(!HashUtil.md5Hash(loginRequestModel.getPassword()).equals(user.getUserPass())){
			redirectAttributes.addFlashAttribute("message", "用户密码错误");
			return new ModelAndView(new RedirectView("/login.html"));
		}
		//用户被冻结
		if(user != null && user.getUserStatus() == Status.USER.FROZEN.ordinal()){
			LOGGER.warn("登录失败：该用户已被冻结！");
			redirectAttributes.addFlashAttribute("message", "该用户已被冻结,请联系管理员！");
			return new ModelAndView(new RedirectView("/login.html"));
		}
		//用户账号已作废
		if(user != null && user.getUserStatus() == Status.USER.DELETE.ordinal()){
			LOGGER.warn("登录失败：该用户账号已作废！");
			redirectAttributes.addFlashAttribute("message", "该用户账号已作废,请联系管理员！");
			return new ModelAndView(new RedirectView("/login.html"));
		}
		
		
		String path = request.getContextPath();
		String basePath = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort()+ path + "/";
		//获取用户第一个一级菜单权限
		String startPage=new String();
		boolean startFlag=true;
		
		//【三】：获取用户权限信息，存储在session中
		List<String> authlist=new ArrayList<String>();
		List<Authority> listAuths = authorityManager.getUserRoleAuths(user.getUserId());
		if(listAuths.size()==0){
			LOGGER.warn("登录失败：用户没有相关权限！");
			redirectAttributes.addFlashAttribute("message", "用户没有相关权限！");
			return new ModelAndView(new RedirectView("/login.html"));
		}
		//pages： 一级菜单权限、  buttons：二级按钮权限
		String pages = "";
		String buttons = "";
		for(Authority ahr:listAuths){
			if(ahr.getActionRelativeUrl()!=null){
				authlist.add(ahr.getActionRelativeUrl());
			}
			if(ahr.getActionType() == Status.AUTHTYPE.PAGE.ordinal()){
				pages += ahr.getActionRemarks()+",";
				if(startFlag&&ahr.getActionRelativeUrl()!=null){
					startPage=ahr.getActionRelativeUrl();
					startFlag=false;
				}
			}else if(ahr.getActionType() == Status.AUTHTYPE.BUTTON.ordinal()){
				buttons += ahr.getActionRemarks()+",";
			}
		}
		//用户登录成功，设置用户状态为登录状态()
		try {
			user.setUserLoginStatus(request.getSession().getId());
			user.setLoginStatus((byte) 1);
			userService.update(user);
		} catch (Exception e) {
			LOGGER.error("get user by username["+loginRequestModel.getUserName()+"] falied!", e);
			redirectAttributes.addFlashAttribute("message", "用户登录失败，数据库连接异常！");
			return new ModelAndView(new RedirectView("/login.html"));
		}
		
		//权限存储到session中（pagesAuth、buttonsAuth控制界面的权限显示效果，authlist 权限url过滤）
		request.getSession().setAttribute("pagesAuth", pages);
		request.getSession().setAttribute("buttonsAuth", buttons);
		request.getSession().setAttribute("authlist", authlist);
		request.getSession().setAttribute("userTenantId", user.getTenantId());
		//用户信息和项目url存储在session中
		request.getSession().setAttribute("user", user);
		request.getSession().setAttribute("basePath", basePath);

		LoginUserCache.USER_ID = user.getUserId();
		LOGGER.info(loginRequestModel.getUserName() + "登录成功");
		
		//用户权限中有概览权限则跳到index.html,没有则跳转到第一个一级菜单权限
		if(authlist.contains("/index.html")){
			return new ModelAndView(new RedirectView("/index.html"));
		}else{
			return new ModelAndView(new RedirectView(startPage));
		}
	}

	@RequestMapping("/logout.html")
	public ModelAndView logout(HttpSession session) {
		User user = (User) session.getAttribute("user");
		try {
			user = userService.getUserByUserId(user.getUserId());
			user.setUserLoginStatus("");
			user.setLoginStatus((byte) 0);
			userService.update(user);
		} catch (Exception e) {
			LOGGER.error("When user login out ,update user["+user.getUserName()+"] falied!", e);
		}
		session.removeAttribute("user");
		/**@bug BOC-50_begin 用户登录状态下线后其他用户登录又变为上线
		 * 解决：清除验证用户是否已登录静态常量，防止验证函数错误更改登录状态
		 * */
		UserStatusConstant.USERSTATUSMAP.remove(user.getUserId());
		/**@bug BOC-50_finish*/
		//用户退出登录，则情况用户id缓存
		LoginUserCache.USER_ID = 0;
		return new ModelAndView("redirect:/login.html");
	}

	@RequestMapping(value = "/captcha", method = { RequestMethod.GET })
	public ResponseEntity<byte[]> image(HttpServletRequest request) throws IOException {
		CaptchaNumber capnumber = CaptchaUtil.getCaptchaNumber();
		HttpSession session = request.getSession(true);
		session.setAttribute("rand", capnumber.getTotalNum().toString());
		return new ResponseEntity<byte[]>(CaptchaUtil.getImage(capnumber.getFirNum(), capnumber.getSecNum()),
				CaptchaUtil.getCaptchaHeaders(), HttpStatus.OK);
	}
}
