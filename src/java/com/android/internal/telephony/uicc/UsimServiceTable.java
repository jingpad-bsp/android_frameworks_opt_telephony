/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.uicc;

import android.annotation.UnsupportedAppUsage;


/**
 * Wrapper class for the USIM Service Table EF.
 * See 3GPP TS 31.102 Release 10 section 4.2.8
 */
public final class UsimServiceTable extends IccServiceTable {
    public enum UsimService {
        PHONEBOOK,
        @UnsupportedAppUsage
        FDN,                                // Fixed Dialing Numbers
        FDN_EXTENSION,                      // FDN extension data in EF_EXT2
        @UnsupportedAppUsage
        SDN,                                // Service Dialing Numbers
        SDN_EXTENSION,                      // SDN extension data in EF_EXT3
        BDN,                                // Barred Dialing Numbers
        BDN_EXTENSION,                      // BDN extension data in EF_EXT4
        OUTGOING_CALL_INFO,
        INCOMING_CALL_INFO,
        @UnsupportedAppUsage
        SM_STORAGE,
        SM_STATUS_REPORTS,
        @UnsupportedAppUsage
        SM_SERVICE_PARAMS,
        ADVICE_OF_CHARGE,
        CAP_CONFIG_PARAMS_2,
        CB_MESSAGE_ID,
        CB_MESSAGE_ID_RANGES,
        GROUP_ID_LEVEL_1,
        GROUP_ID_LEVEL_2,
        @UnsupportedAppUsage
        SPN,                                // Service Provider Name
        USER_PLMN_SELECT,
        @UnsupportedAppUsage
        MSISDN,
        IMAGE,
        LOCALISED_SERVICE_AREAS,
        EMLPP,                              // Enhanced Multi-Level Precedence and Preemption
        EMLPP_AUTO_ANSWER,
        RFU,
        GSM_ACCESS,
        DATA_DL_VIA_SMS_PP,
        DATA_DL_VIA_SMS_CB,
        CALL_CONTROL_BY_USIM,
        MO_SMS_CONTROL_BY_USIM,
        RUN_AT_COMMAND,
        IGNORED_1,
        ENABLED_SERVICES_TABLE,
        APN_CONTROL_LIST,
        DEPERSONALISATION_CONTROL_KEYS,
        COOPERATIVE_NETWORK_LIST,
        GSM_SECURITY_CONTEXT,
        CPBCCH_INFO,
        INVESTIGATION_SCAN,
        MEXE,
        OPERATOR_PLMN_SELECT,
        HPLMN_SELECT,
        EXTENSION_5,                        // Extension data for ICI, OCI, MSISDN in EF_EXT5
        @UnsupportedAppUsage
        PLMN_NETWORK_NAME,
        @UnsupportedAppUsage
        OPERATOR_PLMN_LIST,
        @UnsupportedAppUsage
        MBDN,                               // Mailbox Dialing Numbers
        @UnsupportedAppUsage
        MWI_STATUS,                         // Message Waiting Indication status
        @UnsupportedAppUsage
        CFI_STATUS,                         // Call Forwarding Indication status
        IGNORED_2,
        SERVICE_PROVIDER_DISPLAY_INFO,
        MMS_NOTIFICATION,
        MMS_NOTIFICATION_EXTENSION,         // MMS Notification extension data in EF_EXT8
        GPRS_CALL_CONTROL_BY_USIM,
        MMS_CONNECTIVITY_PARAMS,
        NETWORK_INDICATION_OF_ALERTING,
        VGCS_GROUP_ID_LIST,
        VBS_GROUP_ID_LIST,
        PSEUDONYM,
        IWLAN_USER_PLMN_SELECT,
        IWLAN_OPERATOR_PLMN_SELECT,
        USER_WSID_LIST,
        OPERATOR_WSID_LIST,
        VGCS_SECURITY,
        VBS_SECURITY,
        WLAN_REAUTH_IDENTITY,
        MM_STORAGE,
        GBA,                                // Generic Bootstrapping Architecture
        MBMS_SECURITY,
        DATA_DL_VIA_USSD,
        EQUIVALENT_HPLMN,
        TERMINAL_PROFILE_AFTER_UICC_ACTIVATION,
        EQUIVALENT_HPLMN_PRESENTATION,
        LAST_RPLMN_SELECTION_INDICATION,
        OMA_BCAST_PROFILE,
        GBA_LOCAL_KEY_ESTABLISHMENT,
        TERMINAL_APPLICATIONS,
        SPN_ICON,
        PLMN_NETWORK_NAME_ICON,
        USIM_IP_CONNECTION_PARAMS,
        IWLAN_HOME_ID_LIST,
        IWLAN_EQUIVALENT_HPLMN_PRESENTATION,
        IWLAN_HPLMN_PRIORITY_INDICATION,
        IWLAN_LAST_REGISTERED_PLMN,
        EPS_MOBILITY_MANAGEMENT_INFO,
        @UnsupportedAppUsage
        ALLOWED_CSG_LISTS_AND_INDICATIONS,
        CALL_CONTROL_ON_EPS_PDN_CONNECTION_BY_USIM,
        HPLMN_DIRECT_ACCESS,
        ECALL_DATA,
        @UnsupportedAppUsage
        OPERATOR_CSG_LISTS_AND_INDICATIONS,
        @UnsupportedAppUsage
        SM_OVER_IP,
        @UnsupportedAppUsage
        CSG_DISPLAY_CONTROL,
        IMS_COMMUNICATION_CONTROL_BY_USIM,
        EXTENDED_TERMINAL_APPLICATIONS,
        UICC_ACCESS_TO_IMS,
        NAS_CONFIG_BY_USIM,
        //UNISOC: add for new protocol
        PWS_CONFIG_BY_USIM,
        URI_SUP_BY_UICC,
        EXTENDED_EARFCN_SUP,
        PROSE,
        USAT_APPLICATION_PAIRING,
        MEDIA_TYPE_SUP,
        IMS_CALL_DISCONNECTION_CAUSE,
        URI_SUP_FOR_MO_SHORT_MESSAGE_CONTROL,
        EPDG_CONFIG_INFO_SUP,
        EPDG_CONFIG_INFO_CONFIGURED,
        ACDC_SUP,
        MISSION_CRITICAL_SERVICES,
        EPDG_CONFIG_INFO_FOR_EMERGENCY_SERVICE_SUP,
        EPDG_CONFIG_INFO_FOR_EMERGENCY_SERVICE_CONFIGURED,
        ECall_DATA_OVER_IMS,
        URI_SUP_FOR_SMS_PP_DOWNLOAD,
        FROM_PREFERRED,
        IMS_CONFIG_DATA,
        TV_CONFIG,
        UST_PS_DATA_OFF,
        UST_PS_DATA_OFF_SERVICE_LIST,
        V2X,
        XCAP_CONFIG_DATA,
        EARFCN_LIST_FOR_MTC_NB_IOT_UES,
        NRS_MOBILITY_MANAGEMENT_INFO,
        NR_SECURITY_PARAMETERS,
        SUB_ID_PRAVACY_SUP,
        SUCI_CALCULATION_BY_USIM,
        UAC_ACCESS_ID_SUP,
        CONTROL_PLANE_BASED_TREERING_OF_UE_IN_VPLMN,
        CALL_CONTROL_ON_PDU_SESSION_BY_USIM,
        NRS_OPERATOR_PLMN_LIST,
        SUP_FOR_SUPI_OF_NSI_OR_GLI_OR_GCI,
        UST_PS_DATA_OF_SEPERATE_HOME_AND_ROAMING_LISTS,
        SUP_FOR_URSP_BY_USIM,
        NR_SECURITY_PARAMETERS_EXTENDED,
        MUD_MID_CONFIG_DATA,
        SUP_FOR_TRUSTED_NON_3GPP_ACCESS_NETWORK_BY_USIM,
    }

    public UsimServiceTable(byte[] table) {
        super(table);
    }

    @UnsupportedAppUsage
    public boolean isAvailable(UsimService service) {
        return super.isAvailable(service.ordinal());
    }

    @Override
    protected String getTag() {
        return "UsimServiceTable";
    }

    @Override
    protected Object[] getValues() {
        return UsimService.values();
    }
}
