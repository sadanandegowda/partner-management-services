package io.mosip.pmp.policy.service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.kernel.auth.adapter.model.AuthUserDetails;
import io.mosip.pmp.policy.constant.PolicyCommonConstants;
import io.mosip.pmp.policy.dto.AllowedKycDto;
import io.mosip.pmp.policy.dto.AuthPolicyDto;
import io.mosip.pmp.policy.dto.DataShareDto;
import io.mosip.pmp.policy.dto.FilterDto;
import io.mosip.pmp.policy.dto.PolicyAttributesDto;
import io.mosip.pmp.policy.dto.PolicyCreateRequestDto;
import io.mosip.pmp.policy.dto.PolicyCreateResponseDto;
import io.mosip.pmp.policy.dto.PolicyDto;
import io.mosip.pmp.policy.dto.PolicyGroupCreateRequestDto;
import io.mosip.pmp.policy.dto.PolicyGroupCreateResponseDto;
import io.mosip.pmp.policy.dto.PolicyGroupUpdateRequestDto;
import io.mosip.pmp.policy.dto.PolicyResponseDto;
import io.mosip.pmp.policy.dto.PolicyStatusUpdateRequestDto;
import io.mosip.pmp.policy.dto.PolicyStatusUpdateResponseDto;
import io.mosip.pmp.policy.dto.PolicyUpdateRequestDto;
import io.mosip.pmp.policy.dto.PolicyWithAuthPolicyDto;
import io.mosip.pmp.policy.dto.ResponseWrapper;
import io.mosip.pmp.policy.dto.ShareableAttributesDto;
import io.mosip.pmp.policy.dto.SourceDto;
import io.mosip.pmp.policy.entity.AuthPolicy;
import io.mosip.pmp.policy.entity.AuthPolicyH;
import io.mosip.pmp.policy.entity.PartnerPolicy;
import io.mosip.pmp.policy.entity.PolicyGroup;
import io.mosip.pmp.policy.errorMessages.ErrorMessages;
import io.mosip.pmp.policy.errorMessages.PolicyManagementServiceException;
import io.mosip.pmp.policy.errorMessages.PolicyServiceLogger;
import io.mosip.pmp.policy.repository.AuthPolicyHRepository;
import io.mosip.pmp.policy.repository.AuthPolicyRepository;
import io.mosip.pmp.policy.repository.PartnerPolicyRepository;
import io.mosip.pmp.policy.repository.PolicyGroupRepository;
import io.mosip.pmp.policy.util.PolicyUtil;

/**
 * <p>This class manages business logic before or after performing database operations.</p>
 * This class is performing following operations.</br>
 * 1. Creating the policy group  </br>
 * 2. Creating the auth policies </br>
 * 3. Updating the policy group </br>
 * 4. Updating the policy status </br>
 * 5. Reading/Getting the policy details</br>
 * 
 * @author Nagarjuna Kuchi
 * @version 1.0
 */

@Service
public class PolicyManagementService {

	@Autowired
	private AuthPolicyRepository authPolicyRepository;

	@Autowired
	private PolicyGroupRepository policyGroupRepository;

	@Autowired
	private AuthPolicyHRepository authPolicyHRepository;

	@Autowired
	PartnerPolicyRepository partnerPolicyRepository;

	@Value("${pmp.policy.expiry.period.indays:180 }")
	private int policyExpiryPeriodInDays;

	@Value("${pmp.policy.allowed.authtokens.types}")
	private String allowedAuthTokenTypes;

	@Value("${pmp.policy.schema.url}")
	private String policySchemaUrl;
	
	public static final String ACTIVE_STATUS = "active";
	public static final String NOTACTIVE_STATUS = "de-active";
	
	/**
	 * 
	 * @param requestDto
	 * @return
	 */
	public PolicyGroupCreateResponseDto createPolicyGroup(PolicyGroupCreateRequestDto requestDto) {		
		validatePolicyGroupName(requestDto.getName(),true);	
		PolicyGroup policyGroup = new PolicyGroup();
		policyGroup.setCrBy(getUser());
		policyGroup.setCrDtimes(LocalDateTime.now());
		policyGroup.setIsActive(true);
		policyGroup.setName(requestDto.getName());
		policyGroup.setDescr(requestDto.getDesc());
		policyGroup.setUserId(getUser());	
		policyGroup.setId(PolicyUtil.generateId());
		return savePolicyGroup(policyGroup);
	}

	/**
	 * 
	 * @param Id
	 * @param name
	 * @param descr
	 * @param is_active
	 * @return
	 */
	public PolicyGroupCreateResponseDto updatePolicyGroup(PolicyGroupUpdateRequestDto requestDto, String policyGroupId) {
		Optional<PolicyGroup> policyGroupFromDb = policyGroupRepository.findById(policyGroupId);
		if(policyGroupFromDb.isEmpty()) {
			throw new PolicyManagementServiceException(ErrorMessages.POLICY_ID_NOT_EXISTS.getErrorCode(),
					ErrorMessages.POLICY_ID_NOT_EXISTS.getErrorMessage()); 
		}		
		PolicyGroup policyGroup = policyGroupFromDb.get();
		if(!policyGroupFromDb.get().getName().equals(requestDto.getName())) {
			validatePolicyGroupName(requestDto.getName(),true);
			policyGroup.setName(requestDto.getName());
		}
		if(policyGroup.getIsActive() != requestDto.isActive()) {
			updatePoicyGroupPolicies(policyGroupFromDb.get().getId(),requestDto.isActive());
			policyGroup.setIsActive(requestDto.isActive());
		}
		policyGroup.setDescr(requestDto.getDesc());
		policyGroup.setUpdBy(getUser());
		policyGroup.setUpdDtimes(LocalDateTime.now());
		policyGroup.setUserId(getUser());		
		return savePolicyGroup(policyGroup);
	}

	/**
	 * 
	 * @param policyId
	 * @param status
	 */
	private void updatePoicyGroupPolicies(String policyId, boolean status) {
		List<AuthPolicy> authPolicies = authPolicyRepository.findByPolicyGroupId(policyId);
		for (AuthPolicy authPolicy : authPolicies) {
			if(!status) {
				authPolicy.setIsActive(status);
				authPolicy.setUpdBy(getUser());
				authPolicy.setUpdDtimes(LocalDateTime.now());
				authPolicyRepository.save(authPolicy);
				insertIntoAuthPolicyH(authPolicy);
			}else {
				if(!authPolicy.getValidToDate().isBefore(LocalDateTime.now())){
					authPolicy.setIsActive(status);
				}
				authPolicy.setUpdBy(getUser());
				authPolicy.setUpdDtimes(LocalDateTime.now());				
				authPolicyRepository.save(authPolicy);
				insertIntoAuthPolicyH(authPolicy);
			}
		}
	}
	/**
	 * 
	 * @param requestDto
	 * @throws PolicyManagementServiceException
	 * @throws Exception
	 */
	public PolicyCreateResponseDto createPolicies(PolicyCreateRequestDto requestDto) throws PolicyManagementServiceException, Exception {
		validatePolicyTypes(requestDto.getPolicyType());
		PolicyGroup policyGroup = validatePolicyGroupName(requestDto.getPolicyGroupName(), false);
		validateAuthPolicyName(policyGroup.getId(),requestDto.getName());
		return savePolicy(requestDto.getPolicies(),requestDto.getName(),requestDto.getName(),requestDto.getDesc(),
				policyGroup.getId(), requestDto.getPolicyType(), requestDto.getPolicyGroupName());
	}

	/**
	 * 
	 * @param requestDto
	 * @throws Exception 
	 * @throws PolicyManagementServiceException 
	 */
	public PolicyCreateResponseDto updatePolicies(PolicyUpdateRequestDto requestDto, String policyId) throws PolicyManagementServiceException, Exception {
		PolicyGroup policyGroup = validatePolicyGroupName(requestDto.getPolicyGroupName(), false);
		AuthPolicy authPolicy = checkMappingExists(policyGroup.getId(), policyId, false);		
		AuthPolicy mappedPolicy = authPolicyRepository.findByPolicyGroupAndName(policyGroup.getId(), requestDto.getName());
		if(mappedPolicy != null && !mappedPolicy.getName().equals(authPolicy.getName())) {
			throw new PolicyManagementServiceException(ErrorMessages.AUTH_POLICY_NAME_DUPLICATE_EXCEPTION.getErrorCode(),
					ErrorMessages.AUTH_POLICY_NAME_DUPLICATE_EXCEPTION.getErrorMessage() + requestDto.getName());
		}
		return savePolicy(requestDto.getPolicies(),authPolicy.getName(),requestDto.getName(),
				requestDto.getDesc(),policyGroup.getId(),authPolicy.getPolicy_type(),requestDto.getPolicyGroupName());
	}

	/**
	 * 
	 * @param policy_group_name
	 */
	private PolicyGroup validatePolicyGroupName(String policy_group_name, boolean isExists) {
		PolicyGroup policy_group_by_name = policyGroupRepository.findByName(policy_group_name);
		if(policy_group_by_name == null && !isExists) {
			throw new PolicyManagementServiceException(ErrorMessages.POLICY_GROUP_NAME_NOT_EXISTS.getErrorCode(),
					ErrorMessages.POLICY_GROUP_NAME_NOT_EXISTS.getErrorMessage());			
		}
		if(policy_group_by_name != null && isExists) {
			throw new PolicyManagementServiceException(ErrorMessages.POLICY_GROUP_NAME_DUPLICATE.getErrorCode(),
					ErrorMessages.POLICY_GROUP_NAME_DUPLICATE.getErrorMessage() + policy_group_name);
		}		
		return policy_group_by_name;
	}			

	/**
	 * 
	 * @param policyGroup
	 * @return
	 */
	private PolicyGroupCreateResponseDto savePolicyGroup(PolicyGroup policyGroup) {
		policyGroupRepository.save(policyGroup);
		PolicyGroupCreateResponseDto responseDto = new PolicyGroupCreateResponseDto();
		responseDto.setCr_by(policyGroup.getCrBy());
		responseDto.setCr_dtimes(policyGroup.getCrDtimes());
		responseDto.setDesc(policyGroup.getDescr());
		responseDto.setName(policyGroup.getName());
		responseDto.setId(policyGroup.getId());
		responseDto.setIs_Active(policyGroup.getIsActive());
		responseDto.setUp_by(policyGroup.getUpdBy());
		responseDto.setUpd_dtimes(policyGroup.getUpdDtimes());
		return responseDto;		
	}

	/**
	 * 
	 * @param auth_policy_name
	 * @param isExists
	 * @return
	 */
	private void validateAuthPolicyName(String policyGroupId ,String auth_policy_name) {
		AuthPolicy auth_policy_by_name = authPolicyRepository.findByPolicyGroupAndName(policyGroupId, auth_policy_name);
		if(auth_policy_by_name != null) {
			throw new PolicyManagementServiceException(ErrorMessages.AUTH_POLICY_NAME_DUPLICATE_EXCEPTION.getErrorCode(),
					ErrorMessages.AUTH_POLICY_NAME_DUPLICATE_EXCEPTION.getErrorMessage() + auth_policy_name);
		}
	}

	/**
	 * 
	 * @param policyGroupName
	 * @param policyName
	 * @return
	 * @throws IOException 
	 * @throws JsonMappingException 
	 * @throws JsonParseException 
	 */
	public PolicyResponseDto publishPolicy(String policyGroupName, String policyName) throws JsonParseException, JsonMappingException, IOException {
		AuthPolicy authPolicy = checkMappingExists(policyGroupName, policyName,false);
		if(authPolicy.getIsActive()) {
			throw new PolicyManagementServiceException(ErrorMessages.POLICY_PUBLISHED.getErrorCode(),
					ErrorMessages.POLICY_PUBLISHED.getErrorMessage());
		}
		authPolicy.setPolicySchema(policySchemaUrl);
		authPolicy.setIsActive(true);
		authPolicy.setUpdBy(getUser());
		authPolicy.setValidToDate(LocalDateTime.now().plusDays(policyExpiryPeriodInDays));
		authPolicy.setUpdDtimes(LocalDateTime.now());
		authPolicyRepository.save(authPolicy);
		insertIntoAuthPolicyH(authPolicy);

		Optional<PolicyGroup> policyGroup = policyGroupRepository.findById(authPolicy.getPolicy_group_id());
		return mapPolicyAndPolicyGroup(policyGroup.get(),authPolicy);
	}

	/**
	 * 
	 * @param policyGroup
	 * @param authPolicy
	 * @return
	 * @throws JsonParseException
	 * @throws JsonMappingException
	 * @throws IOException
	 */
	private PolicyResponseDto mapPolicyAndPolicyGroup(PolicyGroup policyGroup, AuthPolicy authPolicy) throws JsonParseException, JsonMappingException, IOException {
		PolicyResponseDto response = new PolicyResponseDto();
		response.setCr_by(authPolicy.getCrBy());
		response.setCr_dtimes(authPolicy.getCrDtimes());
		response.setIs_Active(authPolicy.getIsActive());
		response.setPolicyDesc(authPolicy.getDescr());
		response.setPolicyGroupDesc(policyGroup.getDescr());
		response.setPolicyGroupId(policyGroup.getId());
		response.setPolicyGroupName(policyGroup.getName());
		response.setPolicyGroupStatus(policyGroup.getIsActive());
		response.setPolicyGroup_cr_by(policyGroup.getCrBy());
		response.setPolicyGroup_cr_dtimes(policyGroup.getCrDtimes());
		response.setPolicyGroup_up_by(policyGroup.getUpdBy());
		response.setPolicyGroup_upd_dtimes(policyGroup.getUpdDtimes());
		response.setPolicyId(authPolicy.getId());
		response.setPolicyName(authPolicy.getName());
		response.setPolicyType(authPolicy.getPolicy_type());
		response.setPublishDate(authPolicy.getValidFromDate());
		response.setValidTill(authPolicy.getValidToDate());
		response.setSchema(authPolicy.getPolicySchema());
		response.setStatus(authPolicy.getIsActive() == true ? "PUBLISHED":"DRAFTED");
		response.setUp_by(authPolicy.getUpdBy());
		response.setUpd_dtimes(authPolicy.getUpdDtimes());	
		response.setVersion(authPolicy.getVersion());		
		response.setPolicies(mapPolicyAttributes(authPolicy.getPolicyFileId()));
		return response;
	}

	/**
	 * 
	 * @param request
	 * @param oldPolicyName
	 * @param newPolicyName
	 * @param policyDesc
	 * @param policyGroupId
	 * @param policyType
	 * @param policyGroupName
	 * @return
	 * @throws PolicyManagementServiceException
	 * @throws Exception
	 */
	private PolicyCreateResponseDto savePolicy(PolicyAttributesDto request, String oldPolicyName, String newPolicyName, String policyDesc, 
			String policyGroupId, String policyType, String policyGroupName) 
					throws PolicyManagementServiceException, Exception {		
		AuthPolicy authPolicy = authPolicyRepository.findByPolicyGroupAndName(policyGroupId, oldPolicyName);
		if(authPolicy != null) {			
			authPolicy.setId(authPolicy.getId());			
			authPolicy.setDescr(policyDesc);
			authPolicy.setName(newPolicyName);
			authPolicy.setIsActive(true);
			authPolicy.setIsDeleted(false);	
			authPolicy.setPolicy_group_id(policyGroupId);
			authPolicy.setPolicy_type(policyType);
			authPolicy.SetVersion("1.0");
			authPolicy.setPolicyFileId(generatePolicyJson(request,newPolicyName,policyType).toJSONString());
			authPolicy.setUpdBy(getUser());
			authPolicy.setUpdDtimes(LocalDateTime.now());
		}else {		
			authPolicy = new AuthPolicy();		
			authPolicy.setCrBy(getUser());		
			authPolicy.setId(PolicyUtil.generateId());
			authPolicy.setCrDtimes(LocalDateTime.now());
			authPolicy.setDescr(policyDesc);
			authPolicy.setName(newPolicyName);
			authPolicy.setIsActive(false);
			authPolicy.setPolicy_type(policyType);
			authPolicy.setIsDeleted(false);
			authPolicy.SetVersion("1.0");
			authPolicy.setValidFromDate(LocalDateTime.now());
			authPolicy.setValidToDate(LocalDateTime.now().plusDays(60));
			authPolicy.setPolicy_group_id(policyGroupId);
			authPolicy.setPolicyFileId(generatePolicyJson(request,newPolicyName,policyType).toJSONString());
		}

		authPolicyRepository.save(authPolicy);
		insertIntoAuthPolicyH(authPolicy);

		PolicyCreateResponseDto responseDto = new PolicyCreateResponseDto();
		responseDto.setIs_Active(authPolicy.getIsActive());
		responseDto.setId(authPolicy.getId());
		responseDto.setName(authPolicy.getName());
		responseDto.setDesc(authPolicy.getDescr());
		responseDto.setCr_by(authPolicy.getCrBy());
		responseDto.setCr_dtimes(authPolicy.getCrDtimes());
		responseDto.setUp_by(authPolicy.getUpdBy());
		responseDto.setUpd_dtimes(authPolicy.getUpdDtimes());
		responseDto.setPolicyGroupName(policyGroupName);
		return responseDto;	
	}

	/**
	 * This function inserts the records into auth history table
	 * @param authPolicy
	 */
	private void insertIntoAuthPolicyH(AuthPolicy authPolicy) {
		AuthPolicyH authPolicyH = new AuthPolicyH();		
		authPolicyH.setEffDtimes(new Date());
		authPolicyH.setCrBy(getUser());
		authPolicyH.setCrDtimes(LocalDateTime.now());
		authPolicyH.setDescr(authPolicy.getDescr());
		authPolicyH.setId(PolicyUtil.generateId());
		authPolicyH.setIsActive(authPolicy.getIsActive());
		authPolicyH.setName(authPolicy.getName());
		authPolicyH.setPolicyFileId(authPolicy.getPolicyFileId());
		authPolicyH.setUpdBy(authPolicy.getUpdBy());
		authPolicyH.setUpdDtimes(LocalDateTime.now());
		authPolicyH.setPolicy_group_id(authPolicy.getPolicy_group_id());
		authPolicyH.setPolicy_type(authPolicy.getPolicy_type());
		authPolicyH.SetVersion(authPolicy.getVersion());
		authPolicyH.setValidFromDate(authPolicy.getValidFromDate());
		authPolicyH.setValidToDate(authPolicy.getValidToDate());
		authPolicyH.SetPolicySchema(authPolicy.getPolicySchema());		
		authPolicyHRepository.save(authPolicyH);
	}


	/**
	 * @param statusUpdateRequest
	 * @return
	 */
	public ResponseWrapper<PolicyStatusUpdateResponseDto> updatePolicyStatus(PolicyStatusUpdateRequestDto statusUpdateRequest, String policyGroupId, String policyId) {
		if(!(statusUpdateRequest.getStatus().toLowerCase().equals(ACTIVE_STATUS) || 
				statusUpdateRequest.getStatus().toLowerCase().equals(NOTACTIVE_STATUS))) {
			throw new PolicyManagementServiceException(ErrorMessages.POLICY_STATUS_CODE_EXCEPTION.getErrorCode(),
					ErrorMessages.POLICY_STATUS_CODE_EXCEPTION.getErrorMessage());
		}
		Boolean status = statusUpdateRequest.getStatus().contains("De-Active") ? false : true;		
		AuthPolicy authPolicy = checkMappingExists(policyGroupId, policyId, false);		
		authPolicy.setIsActive(status);
		authPolicy.setUpdBy(getUser());
		authPolicy.setUpdDtimes(LocalDateTime.now());
		authPolicyRepository.save(authPolicy);
		insertIntoAuthPolicyH(authPolicy);		
		ResponseWrapper<PolicyStatusUpdateResponseDto> response = new ResponseWrapper<>();
		PolicyStatusUpdateResponseDto responseDto = new PolicyStatusUpdateResponseDto();
		responseDto.setMessage("status updated successfully");
		response.setResponse(responseDto);
		return response;	
	}

	/**
	 * 
	 * @param uniquePolicyGroupAttribute
	 * @param uniquePolicyAttribute
	 * @param hasToCheckWithName
	 * @return
	 */
	private AuthPolicy checkMappingExists(String uniquePolicyGroupAttribute, String uniquePolicyAttribute, 
			boolean hasToCheckWithName) {
		Optional<PolicyGroup> policyGroup = null;
		Optional<AuthPolicy> authPolicy = null;
		if(hasToCheckWithName) {
			policyGroup = Optional.of(policyGroupRepository.findByName(uniquePolicyGroupAttribute));
			authPolicy = Optional.of(authPolicyRepository.findByName(uniquePolicyAttribute));
		}
		if(!hasToCheckWithName) {
			policyGroup = policyGroupRepository.findById(uniquePolicyGroupAttribute);
			authPolicy = authPolicyRepository.findById(uniquePolicyAttribute);
		}		
		if(policyGroup.isEmpty()) {
			throw new PolicyManagementServiceException(ErrorMessages.POLICY_GROUP_ID_NOT_EXISTS.getErrorCode(),
					ErrorMessages.POLICY_GROUP_ID_NOT_EXISTS.getErrorMessage());			
		}
		if(authPolicy.isEmpty()) {
			throw new PolicyManagementServiceException(ErrorMessages.POLICY_ID_NOT_EXISTS.getErrorCode(),
					ErrorMessages.POLICY_ID_NOT_EXISTS.getErrorMessage());
		}		
		if(!policyGroup.get().getId().equals(authPolicy.get().getPolicy_group_id())){
			throw new PolicyManagementServiceException(ErrorMessages.POLICY_GROUP_POLICY_NOT_MAPPED.getErrorCode(),
					ErrorMessages.POLICY_GROUP_POLICY_NOT_MAPPED.getErrorMessage());
		}
		return authPolicy.get();
	}

	/**
	 * @param policyId
	 * @return
	 * @throws ParseException 
	 * @throws IOException 
	 * @throws FileNotFoundException 
	 */
	public PolicyResponseDto findPolicy(String policyId) throws FileNotFoundException, 
	IOException, ParseException {		
		AuthPolicy authPolicy = getAuthPolicy(policyId);
		PolicyGroup policyGroup = getPolicyGroup(authPolicy.getPolicy_group_id());		
		return mapPolicyAndPolicyGroup(policyGroup, authPolicy);
	}

	/**
	 * 
	 * @return
	 * @throws FileNotFoundException
	 * @throws IOException
	 * @throws ParseException
	 */
	public List<PolicyResponseDto> findAllPolicies() throws FileNotFoundException, IOException, ParseException{		
		List<AuthPolicy> authPolicies = authPolicyRepository.findAll();
		List<PolicyResponseDto> authPolicesResponse = new ArrayList<PolicyResponseDto>();
		for (AuthPolicy authPolicy : authPolicies) {
			authPolicesResponse.add(findPolicy(authPolicy.getId()));
		}
		return authPolicesResponse;
	}


	/**
	 * 
	 * @param policyId
	 * @return
	 */
	private AuthPolicy getAuthPolicy(String policyId) {
		Optional<AuthPolicy> authPolicy = authPolicyRepository.findById(policyId);
		if(authPolicy.isEmpty()) {
			throw new PolicyManagementServiceException(ErrorMessages.POLICY_ID_NOT_EXISTS.getErrorCode(),
					ErrorMessages.POLICY_ID_NOT_EXISTS.getErrorMessage());

		}
		return authPolicy.get();
	}

	/**
	 * 
	 * @param policyGroupId
	 * @return
	 */
	private PolicyGroup getPolicyGroup(String policyGroupId) {
		Optional<PolicyGroup> policyGroup  = policyGroupRepository.findById(policyGroupId);
		if(policyGroup.isEmpty()) {
			throw new PolicyManagementServiceException(ErrorMessages.POLICY_GROUP_ID_NOT_EXISTS.getErrorCode(),
					ErrorMessages.POLICY_GROUP_ID_NOT_EXISTS.getErrorMessage());			
		}
		return policyGroup.get();
	}

	/**
	 * This method is used to find policy mapped to partner api key.
	 * @throws ParseException 
	 * @throws IOException 
	 * @throws FileNotFoundException 
	 * 
	 */
	public PolicyResponseDto getAuthPolicyWithApiKey(String partnerApiKey) throws FileNotFoundException, IOException, ParseException {
		PartnerPolicy partnerPolicy = partnerPolicyRepository.findByApiKey(partnerApiKey);
		if(partnerPolicy == null) {
			PolicyServiceLogger.error("Partner api key not found");
			throw new PolicyManagementServiceException(ErrorMessages.NO_POLICY_AGAINST_APIKEY.getErrorCode(),
					ErrorMessages.NO_POLICY_AGAINST_APIKEY.getErrorMessage());
		}		
		return findPolicy(partnerPolicy.getPolicyId());		
	}

	/**
	 * @param policy
	 * @param name
	 * @return
	 * @throws IOException
	 */
	@SuppressWarnings({ "unchecked" })
	private JSONObject generatePolicyJson(PolicyAttributesDto policy, String name, String policyType) throws PolicyManagementServiceException,
	Exception {
		validatePolicyAttributes(policy,policyType);		
		PolicyServiceLogger.info("Creating policy document");
		JSONObject obj = new JSONObject();
		JSONArray authPolicies = new JSONArray();
		JSONArray shareableAttributes = new JSONArray();
		JSONArray allowedKycAttributes = new JSONArray();
		if(policyType.toUpperCase().equals(PolicyCommonConstants.AUTH_TYPE)) {
			for (AuthPolicyDto authPolicyDto : policy.getAllowedAuthTypes()) {
				JSONObject authObj = new JSONObject();
				authObj.put("authType", authPolicyDto.getAuthType());
				authObj.put("authSubType", authPolicyDto.getAuthSubType());
				authObj.put("mandatory", authPolicyDto.isMandatory());
				authPolicies.add(authObj);
			}
			for (AllowedKycDto allowedKycDto : policy.getAllowedKYCAttributes()) {
				JSONObject allowedKycObj = new JSONObject();
				allowedKycObj.put("attributeName", allowedKycDto.getAttributeName());
				allowedKycAttributes.add(allowedKycObj);
			}
			obj.put("allowedAuthTypes", authPolicies);
			obj.put("authTokenType", policy.getAuthTokenType());
			obj.put("allowedKycAttributes", allowedKycAttributes);		
			return obj;
		}

		for (ShareableAttributesDto shareableAttribute : policy.getShareableAttributes()) {
			JSONObject shareableAttributeObj = new JSONObject();
			shareableAttributeObj.put("attributeName", shareableAttribute.getAttributeName());
			shareableAttributeObj.put("encrypted", shareableAttribute.isEncrypted());
			if(shareableAttribute.getFormat() != null) {
				shareableAttributeObj.put("format", shareableAttribute.getFormat());
			}
//			if(shareableAttribute.getGroup() != null) {
//				shareableAttributeObj.put("group", shareableAttribute.getGroup());
//			}
//			shareableAttributeObj.put("source", getSourceJson(shareableAttribute.getSource()));
			shareableAttributes.add(shareableAttributeObj);			
		}
		JSONObject dataShareObj = new JSONObject();
		dataShareObj.put("validForInMinutes", policy.getDataSharePolicies().getValidForInMinutes());
		dataShareObj.put("transactionsAllowed", policy.getDataSharePolicies().getTransactionsAllowed());
		dataShareObj.put("encryptionType", policy.getDataSharePolicies().getEncryptionType());
		dataShareObj.put("shareDomain", policy.getDataSharePolicies().getShareDomain());	
		dataShareObj.put("typeOfShare", policy.getDataSharePolicies().getTypeOfShare());
		//dataShareObj.put("source", policy.getDataSharePolicies().getSource());
		obj.put("dataSharePolicies", dataShareObj);
		obj.put("shareableAttributes", shareableAttributes);
		return obj;
	}

	@SuppressWarnings({ "unchecked", "unused" })
	private JSONArray getSourceJson(List<SourceDto> sourceDto) {
		JSONArray sourceAttributes = new JSONArray();
		for(SourceDto source : sourceDto) {
			JSONObject sourceObj = new JSONObject();
			sourceObj.put("attribute", source.getAttribute());
			JSONArray filter = getFilterJson(source.getFilter());
			if(filter != null) {			
				sourceObj.put("filter", filter);
			}
			sourceAttributes.add(sourceObj);
		}
		return sourceAttributes;
	}

	@SuppressWarnings("unchecked")
	private JSONArray getFilterJson(List<FilterDto> filter) {
		JSONArray filterAttributes = new JSONArray();
		if(filter != null) {
			for(FilterDto filterDto : filter) {
				JSONObject filterObj = new JSONObject();
				JSONArray subTypeArray = new JSONArray();
				filterObj.put("type", filterDto.getType());
				if(filterDto.getSubType() != null) {
					for(String subType : filterDto.getSubType()) {
						subTypeArray.add(subType);
					}
					filterObj.put("subType", subTypeArray);
				}
				filterAttributes.add(filterObj);
			}		
			return filterAttributes;
		}

		return null;
	}

	/**
	 * 
	 * @param partnerId
	 * @param policyId
	 * @return
	 * @throws JsonParseException
	 * @throws JsonMappingException
	 * @throws IOException
	 */
	public PolicyResponseDto getPartnerMappedPolicy(String partnerId, String policyId) throws JsonParseException, JsonMappingException, IOException {
		PartnerPolicy partnerPolicy = partnerPolicyRepository.findByPartnerId(partnerId,policyId);
		if(partnerPolicy == null) {
			PolicyServiceLogger.error("Partner is not found");
			throw new PolicyManagementServiceException(ErrorMessages.NO_POLICY_AGAINST_PARTNER.getErrorCode(),
					ErrorMessages.NO_POLICY_AGAINST_PARTNER.getErrorMessage());
		}
		Optional<AuthPolicy> authPolicy = authPolicyRepository.findById(policyId);
		if(authPolicy.isEmpty()) {
			throw new PolicyManagementServiceException(ErrorMessages.POLICY_ID_NOT_EXISTS.getErrorCode(),
					ErrorMessages.POLICY_ID_NOT_EXISTS.getErrorMessage());

		}
		if(!partnerPolicy.getPolicyId().equals(policyId)) {
			throw new PolicyManagementServiceException(ErrorMessages.PARTNER_POLICY_NOT_MAPPED.getErrorCode(),
					ErrorMessages.PARTNER_POLICY_NOT_MAPPED.getErrorMessage());

		}		
		Optional<PolicyGroup> policyGroup = policyGroupRepository.findById(authPolicy.get().getPolicy_group_id());
		return mapPolicyAndPolicyGroup(policyGroup.get(), authPolicy.get());
	}

	/**
	 * 
	 * @param policyGroupId
	 * @return
	 * @throws IOException 
	 * @throws JsonMappingException 
	 * @throws JsonParseException 
	 */
	public PolicyWithAuthPolicyDto getPolicyGroupPolicy(String policyGroupId) throws JsonParseException, JsonMappingException, IOException {
		PolicyGroup policyGroup = getPolicyGroup(policyGroupId);		
		List<AuthPolicy> policies = authPolicyRepository.findByPolicyGroupId(policyGroupId);
		List<PolicyDto> policyGroupPolicies = new ArrayList<PolicyDto>();
		for (AuthPolicy authPolicy : policies) {
			policyGroupPolicies.add(mapPolicyToPolicyDto(authPolicy));
		}
		PolicyWithAuthPolicyDto response = new PolicyWithAuthPolicyDto();
		response.setPolicyGroup(policyGroup);
		response.setPolicies(policyGroupPolicies);		
		return response;
	}

	/**
	 * 
	 * @return
	 * @throws JsonParseException
	 * @throws JsonMappingException
	 * @throws IOException
	 */
	public List<PolicyWithAuthPolicyDto> getPolicyGroup() throws JsonParseException, JsonMappingException, IOException{
		List<PolicyGroup> policyGroups = policyGroupRepository.findAll();		
		List<PolicyWithAuthPolicyDto> response = new ArrayList<PolicyWithAuthPolicyDto>();
		for (PolicyGroup policyGroup : policyGroups) {
			PolicyWithAuthPolicyDto policyGroupWthPolicy = new PolicyWithAuthPolicyDto();
			List<AuthPolicy> policies = authPolicyRepository.findByPolicyGroupId(policyGroup.getId());
			List<PolicyDto> policyGroupPolicies = new ArrayList<PolicyDto>();
			for (AuthPolicy authPolicy : policies) {
				policyGroupPolicies.add(mapPolicyToPolicyDto(authPolicy));
			}
			policyGroupWthPolicy.setPolicyGroup(policyGroup);
			policyGroupWthPolicy.setPolicies(policyGroupPolicies);
			response.add(policyGroupWthPolicy);
		}
		return response;
	}


	/**
	 * 
	 * @param authPolicy
	 * @return
	 * @throws JsonParseException
	 * @throws JsonMappingException
	 * @throws IOException
	 */
	private PolicyDto mapPolicyToPolicyDto(AuthPolicy authPolicy) throws JsonParseException, JsonMappingException, IOException {
		PolicyAttributesDto policyAttributes = mapPolicyAttributes(authPolicy.getPolicyFileId());
		PolicyDto policyDto = new PolicyDto();
		policyDto.setAllowedAuthTypes(policyAttributes.getAllowedAuthTypes());
		policyDto.setAuthTokenType(policyAttributes.getAuthTokenType());
		policyDto.setCr_by(authPolicy.getCrBy());
		policyDto.setCr_dtimes(authPolicy.getCrDtimes());
		policyDto.setDataSharePolicies(policyAttributes.getDataSharePolicies());
		policyDto.setAllowedKYCAttributes(policyAttributes.getAllowedKYCAttributes());
		policyDto.setIs_Active(authPolicy.getIsActive());
		policyDto.setPolicyDesc(authPolicy.getDescr());
		policyDto.setPolicyId(authPolicy.getId());
		policyDto.setPolicyName(authPolicy.getName());
		policyDto.setPublishDate(authPolicy.getValidFromDate());
		policyDto.setSchema(authPolicy.getPolicySchema());
		policyDto.setShareableAttributes(policyAttributes.getShareableAttributes());
		policyDto.setStatus(getPolicyStatus(authPolicy.getIsActive()));
		policyDto.setUp_by(authPolicy.getUpdBy());
		policyDto.setUpd_dtimes(authPolicy.getUpdDtimes());
		policyDto.setValidTill(authPolicy.getValidToDate());
		policyDto.setVersion(authPolicy.getVersion());
		return policyDto;
	}

	private String getPolicyStatus(Boolean isActive) {
		return isActive == true ? "PUBLISHED":"DRAFTED";
	}
	@SuppressWarnings("unchecked")
	private PolicyAttributesDto mapPolicyAttributes(String policyFile) throws JsonParseException, JsonMappingException, IOException {
		PolicyAttributesDto dto = new PolicyAttributesDto();
		Map<?, ?> readValue = new ObjectMapper().readValue(policyFile, Map.class);
		dto.setAllowedAuthTypes((List<AuthPolicyDto>) readValue.get("allowedAuthTypes"));
		dto.setAllowedKYCAttributes((List<AllowedKycDto>) readValue.get("allowedKycAttributes"));
		dto.setShareableAttributes((List<ShareableAttributesDto>) readValue.get("shareableAttributes"));
		ObjectMapper mapper = new ObjectMapper();
		DataShareDto dataShareDto = mapper.convertValue(readValue.get("dataSharePolicies"), DataShareDto.class);
		dto.setDataSharePolicies(dataShareDto);
		dto.setAuthTokenType((String)readValue.get("authTokenType"));
		return dto;
	}

	/**
	 * 
	 * @return
	 */
	private String getUser() {
		if (Objects.nonNull(SecurityContextHolder.getContext())
				&& Objects.nonNull(SecurityContextHolder.getContext().getAuthentication())
				&& Objects.nonNull(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
				&& SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof AuthUserDetails) {
			return ((AuthUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal())
					.getUserId();
		} else {
			return "system";
		}
	}

	/**
	 * 
	 * @param policyAttributesDto
	 * @param policyType
	 */
	private void validatePolicyAttributes(PolicyAttributesDto policyAttributesDto,String policyType) {
		String[] allowedAuthTokenType = allowedAuthTokenTypes.toString().split(",");
		if(policyType.toUpperCase().equals(PolicyCommonConstants.AUTH_TYPE)) {
			if(policyAttributesDto.getAllowedAuthTypes() == null) {
				throw new PolicyManagementServiceException(ErrorMessages.MISSING_INPUT_PARAMETER.getErrorCode(),
						ErrorMessages.MISSING_INPUT_PARAMETER.getErrorMessage() + "allowedAuthTypes");

			}
			if(policyAttributesDto.getAuthTokenType() == null) {
				throw new PolicyManagementServiceException(ErrorMessages.MISSING_INPUT_PARAMETER.getErrorCode(),
						ErrorMessages.MISSING_INPUT_PARAMETER.getErrorMessage() + "authTokenType");			
			}
			if(!(Arrays.stream(allowedAuthTokenType)).anyMatch(policyAttributesDto.getAuthTokenType() :: equals)){
				throw new PolicyManagementServiceException(ErrorMessages.AUTH_TOKEN_TYPE_NOT_ALLOWED.getErrorCode(),
						ErrorMessages.AUTH_TOKEN_TYPE_NOT_ALLOWED.getErrorMessage());
			}
			if(policyAttributesDto.getDataSharePolicies() != null) {
				throw new PolicyManagementServiceException(ErrorMessages.DATASHARE_ATTRIBUTES_NOT_REQUIRED.getErrorCode(),
						ErrorMessages.DATASHARE_ATTRIBUTES_NOT_REQUIRED.getErrorMessage() + "dataSharePolicies");

			}
			if(policyAttributesDto.getShareableAttributes() != null) {
				throw new PolicyManagementServiceException(ErrorMessages.SHAREABLE_ATTRIBUTES_NOT_REQUIRED.getErrorCode(),
						ErrorMessages.SHAREABLE_ATTRIBUTES_NOT_REQUIRED.getErrorMessage() + "shareableAttributes");
			}
		}else {
			if(policyAttributesDto.getAllowedAuthTypes() != null) {
				throw new PolicyManagementServiceException(ErrorMessages.AUTH_TYPES_NOT_REQUIRED.getErrorCode(),
						ErrorMessages.AUTH_TYPES_NOT_REQUIRED.getErrorMessage() + policyType);

			}
			if(policyAttributesDto.getAllowedKYCAttributes() != null) {
				throw new PolicyManagementServiceException(ErrorMessages.ALLOWED_KYC_ATTRIBUTES_NOT_REQUIRED.getErrorCode(),
						ErrorMessages.ALLOWED_KYC_ATTRIBUTES_NOT_REQUIRED.getErrorMessage() + policyType);

			}
			if(policyAttributesDto.getDataSharePolicies() == null) {
				throw new PolicyManagementServiceException(ErrorMessages.MISSING_INPUT_PARAMETER.getErrorCode(),
						ErrorMessages.MISSING_INPUT_PARAMETER.getErrorMessage() + "dataSharePolicies");

			}
			if(policyAttributesDto.getShareableAttributes() == null) {
				throw new PolicyManagementServiceException(ErrorMessages.MISSING_INPUT_PARAMETER.getErrorCode(),
						ErrorMessages.MISSING_INPUT_PARAMETER.getErrorMessage() + "shareableAttributes");
			}
		}
	}

	/**
	 * 
	 * @param policyType
	 */
	private void validatePolicyTypes(String policyType) {
		if(!Arrays.stream(PolicyCommonConstants.allowed_Policy_Types.split("/")).anyMatch(policyType::equalsIgnoreCase)){
			throw new PolicyManagementServiceException(ErrorMessages.POLICY_TYPE_NOT_ALLOWED.getErrorCode(),
					ErrorMessages.POLICY_TYPE_NOT_ALLOWED.getErrorMessage());
		}
	}
}
