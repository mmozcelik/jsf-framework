package com.ozguryazilim.zoro.core.auth;

import java.io.Serializable;
import java.util.List;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.apache.directory.shared.ldap.model.cursor.EntryCursor;
import org.apache.directory.shared.ldap.model.exception.LdapException;
import org.apache.directory.shared.ldap.model.message.BindRequest;
import org.apache.directory.shared.ldap.model.message.BindRequestImpl;
import org.apache.directory.shared.ldap.model.message.BindResponse;
import org.apache.directory.shared.ldap.model.message.ResultCodeEnum;
import org.apache.directory.shared.ldap.model.message.SearchScope;
import org.apache.directory.shared.ldap.model.name.Dn;
import org.apache.directory.shared.util.Base64;

import com.ozguryazilim.zoro.core.auth.entity.ZoroUser;
import com.ozguryazilim.zoro.core.db.DBEntityManager;
import com.ozguryazilim.zoro.core.settings.ZoroSettings;
import com.ozguryazilim.zoro.core.util.JSFUtility;
import com.ozguryazilim.zoro.core.util.MessageUtility;

@Singleton
@Named
public class AuthenticationService implements Serializable {
	private static final long	serialVersionUID	= -4056582541119391316L;

	private LDAPLazyDataModel	ldapDataModel		= new LDAPLazyDataModel();

	@Inject
	private DBEntityManager		entityManager;

	public boolean authenticate(String username, String password) {
		boolean result = authenticateByDB(username, password);

		if (!result) {
			result = authenticateByLDAP(username, password);
		}

		return result;
	}

	@SuppressWarnings("unchecked")
	public boolean authenticateByDB(String username, String password) {
		boolean result = false;
		List<ZoroUser> users = entityManager.executeParameterizedSelectQuery("SELECT user FROM ZoroUser user WHERE user.username = ? and user.deleted = false",
				new Object[] { username });

		if (users.size() > 0) {
			ZoroUser user = users.get(0);
			if (!user.isBlocked()) {
				if (user.getPassword().equals(Base64.encode(password.getBytes()))) {
					result = true;
				}
				else {
					Logger.getLogger(AuthenticationService.class.getName()).severe("Wrong password for the username : " + username);
				}
			}
			else {
				Logger.getLogger(AuthenticationService.class.getName()).severe("User is blocked : " + username);
			}
		}

		return result;
	}

	public boolean authenticateByLDAP(String username, String password) {
		boolean result = false;

		try {
			LdapNetworkConnection connection = getConnection();
			connection.connect();
			connection.bind();

			// get cn for the given attribute
			EntryCursor userEntries = connection.search(ZoroSettings.settingsHolder.getLdapSearchDN(), "(&(objectclass=person)(" + ZoroSettings.settingsHolder.getLdapLoginAttr()
					+ "=" + username + "))", SearchScope.SUBTREE);

			Dn dnOfUser = null;
			if (userEntries.next()) {
				dnOfUser = userEntries.get().getDn();
			}

			if (dnOfUser != null) {

				BindRequest request = new BindRequestImpl();
				request.setDn(dnOfUser);
				request.setCredentials(password);
				request.setSimple(true);

				LdapNetworkConnection tmpConnection = getConnection();
				BindResponse response = tmpConnection.bind(request);
				if (response.getLdapResult().getResultCode().equals(ResultCodeEnum.SUCCESS)) {
					result = true;
				}
				else {
					Logger.getLogger(AuthenticationService.class.getName()).severe("Wrong password for the username : " + username);
				}

				tmpConnection.close();
			}
			else {
				Logger.getLogger(AuthenticationService.class.getName()).info("No cn for the username : " + username);
			}

			connection.close();

		}
		catch (LdapException e) {
			e.printStackTrace();
			JSFUtility.addErrorMessage(null, MessageUtility.get("default", "errorLDAPConnection"));
			Logger.getLogger(AuthenticationService.class.getName()).severe("LDAP exception for the username : " + username);
			result = false;
		}
		catch (Exception e) {
			e.printStackTrace();
			Logger.getLogger(AuthenticationService.class.getName()).severe("Authentication error for the username : " + username);
			result = false;
		}
		return result;
	}

	public LDAPLazyDataModel getLdapDataModel() {
		return ldapDataModel;
	}

	public static LdapNetworkConnection getConnection() {
		LdapConnectionConfig config = new LdapConnectionConfig();

		config.setLdapHost(ZoroSettings.settingsHolder.getLdapHost());
		config.setLdapPort(ZoroSettings.settingsHolder.getLdapPort());
		config.setName(ZoroSettings.settingsHolder.getLdapUser());
		config.setCredentials(ZoroSettings.settingsHolder.getLdapPass());
		config.setUseSsl(false);

		LdapNetworkConnection connection = new LdapNetworkConnection(config);
		connection.setTimeOut(ZoroSettings.LDAP_TIMEOUT);

		return connection;
	}
}
