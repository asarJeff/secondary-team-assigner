package com.asar.speedx.secondary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "sap")
public class SapConfig {

    private String baseUrl;
    private String caseEndpoint;
    private String username;
    private String password;

    private SecondaryTeam secondaryTeam;
    private StatusCodes statusCodes;
    private List<String> excludeLifeCycleStatuses;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getCaseEndpoint() { return caseEndpoint; }
    public void setCaseEndpoint(String caseEndpoint) { this.caseEndpoint = caseEndpoint; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public SecondaryTeam getSecondaryTeam() { return secondaryTeam; }
    public void setSecondaryTeam(SecondaryTeam secondaryTeam) { this.secondaryTeam = secondaryTeam; }

    public StatusCodes getStatusCodes() { return statusCodes; }
    public void setStatusCodes(StatusCodes statusCodes) { this.statusCodes = statusCodes; }

    public List<String> getExcludeLifeCycleStatuses() { return excludeLifeCycleStatuses; }
    public void setExcludeLifeCycleStatuses(List<String> excludeLifeCycleStatuses) { this.excludeLifeCycleStatuses = excludeLifeCycleStatuses; }

    public static class SecondaryTeam {
        private String partyRole;
        private String opsPartyId;
        private String csPartyId;

        public String getPartyRole() { return partyRole; }
        public void setPartyRole(String partyRole) { this.partyRole = partyRole; }

        public String getOpsPartyId() { return opsPartyId; }
        public void setOpsPartyId(String opsPartyId) { this.opsPartyId = opsPartyId; }

        public String getCsPartyId() { return csPartyId; }
        public void setCsPartyId(String csPartyId) { this.csPartyId = csPartyId; }
    }

    public static class StatusCodes {
        private String awaitingOps;
        private String awaitingCS;

        public String getAwaitingOps() { return awaitingOps; }
        public void setAwaitingOps(String awaitingOps) { this.awaitingOps = awaitingOps; }

        public String getAwaitingCS() { return awaitingCS; }
        public void setAwaitingCS(String awaitingCS) { this.awaitingCS = awaitingCS; }
    }
}