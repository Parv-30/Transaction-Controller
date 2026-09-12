package com.ledger.ledgerservice.fx;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "fx")
public class FxClearingAccountsProperties {

    private final Map<String, String> clearingAccounts = new HashMap<>();

    public Map<String, String> getClearingAccounts() {
        return clearingAccounts;
    }

    public String get(String currency) {
        return clearingAccounts.get(currency);
    }
}
