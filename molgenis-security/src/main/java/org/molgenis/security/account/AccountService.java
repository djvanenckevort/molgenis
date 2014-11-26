package org.molgenis.security.account;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.molgenis.auth.MolgenisGroup;
import org.molgenis.auth.MolgenisGroupMember;
import org.molgenis.auth.MolgenisUser;
import org.molgenis.data.DataService;
import org.molgenis.data.MolgenisDataException;
import org.molgenis.data.support.QueryImpl;
import org.molgenis.framework.server.MolgenisSettings;
import org.molgenis.security.runas.RunAsSystem;
import org.molgenis.security.user.MolgenisUserException;
import org.molgenis.security.user.MolgenisUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class AccountService
{
	private static final Logger logger = Logger.getLogger(AccountService.class);

	public static final String KEY_PLUGIN_AUTH_ACTIVATIONMODE = "plugin.auth.activation_mode";
	public static final String KEY_PLUGIN_AUTH_ENABLE_SELFREGISTRATION = "plugin.auth.enable_self_registration";
	public static final String ALL_USER_GROUP = "All Users";
	private static final String KEY_APP_NAME = "app.name";
	private static final ActivationMode DEFAULT_ACTIVATION_MODE = ActivationMode.ADMIN;
	private static final String DEFAULT_APP_NAME = "MOLGENIS";

	@Autowired
	private DataService dataService;

	@Autowired
	private MolgenisSettings molgenisSettings;

	@Autowired
	private JavaMailSender mailSender;

	@Autowired
	private MolgenisUserService molgenisUserService;

	@RunAsSystem
	public void createUser(MolgenisUser molgenisUser, String baseActivationUri)
	{
		// collect activation info
		String activationCode = UUID.randomUUID().toString();
		List<String> activationEmailAddresses;
		switch (getActivationMode())
		{
			case ADMIN:
				activationEmailAddresses = molgenisUserService.getSuEmailAddresses();
				if (activationEmailAddresses == null || activationEmailAddresses.isEmpty()) throw new MolgenisDataException(
						"Administrator account is missing required email address");
				break;
			case USER:
				String activationEmailAddress = molgenisUser.getEmail();
				if (activationEmailAddress == null || activationEmailAddress.isEmpty()) throw new MolgenisDataException(
						"User '" + molgenisUser.getUsername() + "' is missing required email address");
				activationEmailAddresses = Arrays.asList(activationEmailAddress);
				break;
			default:
				throw new RuntimeException("unknown activation mode: " + getActivationMode());
		}

		// create user
		molgenisUser.setActivationCode(activationCode);
		molgenisUser.setActive(false);
		dataService.add(MolgenisUser.ENTITY_NAME, molgenisUser);
		logger.debug("created user " + molgenisUser.getUsername());

		// add user to group
		MolgenisGroup group = dataService.findOne(MolgenisGroup.ENTITY_NAME,
				new QueryImpl().eq(MolgenisGroup.NAME, ALL_USER_GROUP), MolgenisGroup.class);
		if (group != null)
		{
			MolgenisGroupMember molgenisGroupMember = new MolgenisGroupMember();
			molgenisGroupMember.setMolgenisGroup(group);
			molgenisGroupMember.setMolgenisUser(molgenisUser);
			dataService.add(MolgenisGroupMember.ENTITY_NAME, molgenisGroupMember);
		}

		// send activation email
		URI activationUri = URI.create(baseActivationUri + '/' + activationCode);

		SimpleMailMessage mailMessage = new SimpleMailMessage();
		mailMessage.setTo(activationEmailAddresses.toArray(new String[]
		{}));
		mailMessage.setSubject("User registration for " + getAppName());
		mailMessage.setText(createActivationEmailText(molgenisUser, activationUri));
		mailSender.send(mailMessage);
		logger.debug("send activation email for user " + molgenisUser.getUsername() + " to "
				+ StringUtils.join(activationEmailAddresses, ','));
	}

	/**
	 * Activate a registered user
	 * 
	 * @param activationCode
	 */
	@RunAsSystem
	public void activateUser(String activationCode)
	{
		MolgenisUser molgenisUser = dataService.findOne(MolgenisUser.ENTITY_NAME,
				new QueryImpl().eq(MolgenisUser.ACTIVE, false).and().eq(MolgenisUser.ACTIVATIONCODE, activationCode),
				MolgenisUser.class);

		if (molgenisUser != null)
		{
			molgenisUser.setActive(true);
			dataService.update(MolgenisUser.ENTITY_NAME, molgenisUser);

			// send activated email to user
			SimpleMailMessage mailMessage = new SimpleMailMessage();
			mailMessage.setTo(molgenisUser.getEmail());
			mailMessage.setSubject("Your registration request for " + getAppName());
			mailMessage.setText(createActivatedEmailText(molgenisUser, getAppName()));
			mailSender.send(mailMessage);
		}
		else
		{
			throw new MolgenisUserException("Invalid activation code or account already activated.");
		}
	}

	@RunAsSystem
	public void changePassword(String username, String newPassword)
	{
		MolgenisUser molgenisUser = dataService.findOne(MolgenisUser.ENTITY_NAME,
				new QueryImpl().eq(MolgenisUser.USERNAME, username), MolgenisUser.class);

		if (molgenisUser == null)
		{
			throw new MolgenisUserException("Unknown user [" + username + "]");
		}

		molgenisUser.setPassword(newPassword);
		molgenisUser.setChangePassword(false);
		dataService.update(MolgenisUser.ENTITY_NAME, molgenisUser);

		logger.info("Changed password of user [" + username + "]");
	}

	@RunAsSystem
	public void resetPassword(String userEmail)
	{
		MolgenisUser molgenisUser = dataService.findOne(MolgenisUser.ENTITY_NAME,
				new QueryImpl().eq(MolgenisUser.EMAIL, userEmail), MolgenisUser.class);

		if (molgenisUser != null)
		{
			String newPassword = UUID.randomUUID().toString().substring(0, 8);
			molgenisUser.setPassword(newPassword);
			dataService.update(MolgenisUser.ENTITY_NAME, molgenisUser);

			// send password reseted email to user
			SimpleMailMessage mailMessage = new SimpleMailMessage();
			mailMessage.setTo(molgenisUser.getEmail());
			mailMessage.setSubject("Your new password request");
			mailMessage.setText(createPasswordResettedEmailText(newPassword));
			mailSender.send(mailMessage);
		}
		else
		{
			throw new MolgenisUserException("Invalid email address.");
		}
	}

	public ActivationMode getActivationMode()
	{
		String activationModeStr = molgenisSettings.getProperty(KEY_PLUGIN_AUTH_ACTIVATIONMODE);
		return ActivationMode.from(activationModeStr, DEFAULT_ACTIVATION_MODE);
	}

	private String createActivationEmailText(MolgenisUser user, URI activationUri)
	{
		StringBuilder strBuilder = new StringBuilder();
		strBuilder.append("User registration for ").append(getAppName()).append('\n');
		strBuilder.append("User name: ").append(user.getUsername()).append(" Full name: ").append(user.getFirstName());
		strBuilder.append(' ').append(user.getLastName()).append('\n');
		strBuilder.append("In order to activate the user visit the following URL:").append('\n');
		strBuilder.append(activationUri).append('\n').append('\n');
		return strBuilder.toString();
	}

	private String createActivatedEmailText(MolgenisUser user, String appName)
	{
		StringBuilder strBuilder = new StringBuilder();
		strBuilder.append("Dear ").append(user.getFirstName()).append(" ").append(user.getLastName()).append(",\n\n");
		strBuilder.append("your registration request for ").append(appName).append(" was approved.\n");
		strBuilder.append("Your account is now active.\n");
		return strBuilder.toString();
	}

	private String createPasswordResettedEmailText(String newPassword)
	{
		StringBuilder strBuilder = new StringBuilder();
		strBuilder.append("Somebody, probably you, requested a new password for ").append(getAppName()).append(".\n");
		strBuilder.append("The new password is: ").append(newPassword).append('\n');
		strBuilder.append("Note: we strongly recommend you reset your password after log-in!");
		return strBuilder.toString();
	}

	// TODO move to utility class
	private String getAppName()
	{
		return molgenisSettings.getProperty(KEY_APP_NAME, DEFAULT_APP_NAME);
	}

	static enum ActivationMode
	{
		ADMIN, USER;

		public static ActivationMode from(String str, ActivationMode defaultActivationMode)
		{
			if (str == null) return defaultActivationMode;
			for (ActivationMode activationMode : ActivationMode.values())
				if (str.equalsIgnoreCase(activationMode.toString())) return activationMode;
			return defaultActivationMode;
		}
	}

	public boolean isSelfRegistrationEnabled()
	{
		return molgenisSettings.getBooleanProperty(KEY_PLUGIN_AUTH_ENABLE_SELFREGISTRATION, true);
	}
}
