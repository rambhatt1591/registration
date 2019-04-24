package io.mosip.registration.processor.packet.uploader.service.impl;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.fsadapter.exception.FSAdapterException;
import io.mosip.kernel.core.fsadapter.spi.FileSystemAdapter;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.HMACUtils;
import io.mosip.kernel.core.virusscanner.exception.VirusScannerException;
import io.mosip.kernel.core.virusscanner.spi.VirusScanner;
import io.mosip.registration.processor.core.abstractverticle.MessageDTO;
import io.mosip.registration.processor.core.code.ApiName;
import io.mosip.registration.processor.core.code.EventId;
import io.mosip.registration.processor.core.code.EventName;
import io.mosip.registration.processor.core.code.EventType;
import io.mosip.registration.processor.core.code.RegistrationExceptionTypeCode;
import io.mosip.registration.processor.core.code.RegistrationTransactionStatusCode;
import io.mosip.registration.processor.core.code.RegistrationTransactionTypeCode;
import io.mosip.registration.processor.core.constant.LoggerFileConstant;
import io.mosip.registration.processor.core.exception.util.PlatformErrorMessages;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.packet.dto.SftpJschConnectionDto;
import io.mosip.registration.processor.core.spi.filesystem.manager.FileManager;
import io.mosip.registration.processor.core.util.RegistrationExceptionMapperUtil;
import io.mosip.registration.processor.packet.manager.dto.DirectoryPathDto;
import io.mosip.registration.processor.packet.uploader.archiver.util.PacketArchiver;
import io.mosip.registration.processor.packet.uploader.decryptor.Decryptor;
import io.mosip.registration.processor.packet.uploader.exception.PacketNotFoundException;
import io.mosip.registration.processor.packet.uploader.service.PacketUploaderService;
import io.mosip.registration.processor.packet.uploader.util.StatusMessage;
import io.mosip.registration.processor.rest.client.audit.builder.AuditLogRequestBuilder;
import io.mosip.registration.processor.status.code.RegistrationStatusCode;
import io.mosip.registration.processor.status.dto.InternalRegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.dto.SyncRegistrationDto;
import io.mosip.registration.processor.status.dto.SyncResponseDto;
import io.mosip.registration.processor.status.entity.SyncRegistrationEntity;
import io.mosip.registration.processor.status.exception.TablenotAccessibleException;
import io.mosip.registration.processor.status.service.RegistrationStatusService;
import io.mosip.registration.processor.status.service.SyncRegistrationService;

/**
 * The Class PacketUploaderServiceImpl.
 * @author Rishabh Keshari
 *
 */
@RefreshScope
@Component
public class PacketUploaderServiceImpl implements PacketUploaderService<MessageDTO> {

	/** The reg proc logger. */
	private static Logger regProcLogger = RegProcessorLogger.getLogger(PacketUploaderServiceImpl.class);

	/** The Constant USER. */
	private static final String USER = "MOSIP_SYSTEM";

	/** The hdfs adapter. */
	@Autowired
	private FileSystemAdapter hdfsAdapter;

	/** The ppk file location. */
	//@Value("${registration.processor.server.ppk.filelocation}")
	private String ppkFileLocation;

	/** The ppk file name. */
	//@Value("${registration.processor.server.ppk.filename}")
	private String ppkFileName;

	/** The host. */
	@Value("${registration.processor.dmz.server.host}")
	private String host;

	/** The dmz port. */
	@Value("${registration.processor.dmz.server.port}")
	private String dmzPort;

	/** The dmz server user. */
	@Value("${registration.processor.dmz.server.user}")
	private String dmzServerUser;

	/** The dmz server protocal. */
	@Value("${registration.processor.dmz.server.protocal}")
	private String dmzServerProtocal;

	/** The file manager. */
	@Autowired
	private FileManager<DirectoryPathDto, InputStream> fileManager;

	/** The sync registration service. */
	@Autowired
	private SyncRegistrationService<SyncResponseDto, SyncRegistrationDto> syncRegistrationService;

	/** The registration status service. */
	@Autowired
	private RegistrationStatusService<String, InternalRegistrationStatusDto, RegistrationStatusDto> registrationStatusService;

	/** The core audit request builder. */
	@Autowired
	private AuditLogRequestBuilder auditLogRequestBuilder;

	/** The registration status mapper util. */
	RegistrationExceptionMapperUtil registrationStatusMapperUtil = new RegistrationExceptionMapperUtil();

	/** The packet receiver stage. */

	@Value("${registration.processor.packet.ext}")
	private String extention;

	/** The file size. */
	@Value("${registration.processor.max.file.size}")
	private String fileSize;

	/** The registration exception mapper util. */
	RegistrationExceptionMapperUtil registrationExceptionMapperUtil = new RegistrationExceptionMapperUtil();

	/** The reg entity. */
	private SyncRegistrationEntity regEntity;

	/** The virus scanner service. */
	@Autowired
	private VirusScanner<Boolean, InputStream> virusScannerService;

	/** The decryptor. */
	@Autowired
	private Decryptor decryptor;

	/** The max retry count. */
	@Value("${registration.processor.uploader.max.retry.count}")
	private int maxRetryCount;

	/** The description. */
	private String description = "";

	/** The is transaction successful. */
	boolean isTransactionSuccessful = false;

	/** The registration id. */
	private String registrationId;

	/** The packet archiver. */
	@Autowired
	private PacketArchiver packetArchiver;

	/** The dto. */
	InternalRegistrationStatusDto dto = new InternalRegistrationStatusDto();

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.id.issuance.packet.handler.service.PacketUploadService#
	 * validatePacket( java.lang.Object)
	 */



	@Override
	public MessageDTO validateAndUploadPacket(String regId) {

		MessageDTO messageDTO = new MessageDTO();
		messageDTO.setInternalError(false);
		messageDTO.setIsValid(false);
		InputStream decryptedData = null;

		this.registrationId = regId;
		isTransactionSuccessful = false;

		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
				registrationId, "PacketUploaderServiceImpl::validateAndUploadPacket()::entry");
		messageDTO.setRid(registrationId);

		regEntity = syncRegistrationService.findByRegistrationId(registrationId);

		try  {

			SftpJschConnectionDto jschConnectionDto=new SftpJschConnectionDto();
			jschConnectionDto.setHost(host);
			jschConnectionDto.setPort(Integer.parseInt(dmzPort));
			jschConnectionDto.setPpkFileLocation(ppkFileLocation+File.separator+ppkFileName);
			jschConnectionDto.setUser(dmzServerUser);
			jschConnectionDto.setProtocal(dmzServerProtocal);

			byte[] encryptedByteArray=fileManager.getFile(DirectoryPathDto.LANDING_ZONE, regId, jschConnectionDto);

			if(encryptedByteArray != null) {

				if(validateHashCode(new ByteArrayInputStream(encryptedByteArray))) {

					if(scanFile(new ByteArrayInputStream(encryptedByteArray))) {

						decryptedData = decryptor.decrypt(new ByteArrayInputStream(encryptedByteArray),registrationId);

						if(scanFile(decryptedData)) {

							dto = registrationStatusService.getRegistrationStatus(registrationId);
							int retrycount = (dto.getRetryCount() == null) ? 0 : dto.getRetryCount() + 1;
							dto.setRetryCount(retrycount);
							dto.setLatestTransactionTypeCode(RegistrationTransactionTypeCode.UPLOAD_PACKET.toString());
							dto.setRegistrationStageName(this.getClass().getSimpleName());
							if (retrycount < getMaxRetryCount()) {
								regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(),
										LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
										"PacketUploaderServiceImpl::validateAndUploadPacket()::entry");

								messageDTO = uploadpacket(dto,decryptedData, messageDTO,jschConnectionDto);
								if (messageDTO.getIsValid()) {
									dto.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.SUCCESS.toString());
									isTransactionSuccessful = true;
									description = "Packet uploaded to DFS successfully for registrationId " + this.registrationId;
									regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
											LoggerFileConstant.REGISTRATIONID.toString(), registrationId, description);
									regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(),
											LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
											"PacketUploaderServiceImpl::validateAndUploadPacket()::exit");

								}
							} else {

								messageDTO.setInternalError(Boolean.TRUE);
								description = "Failure in uploading the packet to Packet Store" + registrationId;
								dto.setLatestTransactionStatusCode(registrationStatusMapperUtil
										.getStatusCode(RegistrationExceptionTypeCode.PACKET_UPLOADER_FAILED));
								dto.setStatusCode(RegistrationStatusCode.PACKET_UPLOAD_TO_PACKET_STORE_FAILED.toString());
								dto.setStatusComment("Packet upload to packet store failed for " + registrationId);
								dto.setUpdatedBy(USER);
							}
						}
					}
				}
			}


		} catch (TablenotAccessibleException e) {
			dto.setLatestTransactionStatusCode(registrationStatusMapperUtil
					.getStatusCode(RegistrationExceptionTypeCode.TABLE_NOT_ACCESSIBLE_EXCEPTION));
			messageDTO.setInternalError(true);
			messageDTO.setIsValid(false);
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId, PlatformErrorMessages.RPR_RGS_REGISTRATION_TABLE_NOT_ACCESSIBLE.name()
					+ ExceptionUtils.getStackTrace(e));

			description = "Registration status TablenotAccessibleException for registrationId " + this.registrationId
					+ "::" + e.getMessage();

		} catch (PacketNotFoundException ex) {
			dto.setLatestTransactionStatusCode(registrationStatusMapperUtil
					.getStatusCode(RegistrationExceptionTypeCode.PACKET_NOT_FOUND_EXCEPTION));
			messageDTO.setInternalError(true);
			messageDTO.setIsValid(false);
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId,
					PlatformErrorMessages.RPR_PUM_PACKET_NOT_FOUND_EXCEPTION.name() + ExceptionUtils.getStackTrace(ex));
			description = "Packet not found in DFS for registrationId " + registrationId + "::" + ex.getMessage();
		} catch (FSAdapterException e) {
			dto.setLatestTransactionStatusCode(
					registrationStatusMapperUtil.getStatusCode(RegistrationExceptionTypeCode.FSADAPTER_EXCEPTION));
			messageDTO.setInternalError(true);
			messageDTO.setIsValid(false);
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId, PlatformErrorMessages.RPR_PUM_PACKET_STORE_NOT_ACCESSIBLE.name() + e.getMessage());

			description = "DFS not accessible for registrationId " + registrationId + "::" + e.getMessage();
		} catch (IOException e) {
			dto.setLatestTransactionStatusCode(
					registrationStatusMapperUtil.getStatusCode(RegistrationExceptionTypeCode.IOEXCEPTION));
			messageDTO.setIsValid(false);
			messageDTO.setInternalError(true);
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId,
					PlatformErrorMessages.RPR_SYS_IO_EXCEPTION.name() + ExceptionUtils.getStackTrace(e));
			description = "Virus scan decryption path not found for registrationId " + registrationId + "::"
					+ e.getMessage();

		} catch (Exception e) {
			dto.setLatestTransactionStatusCode(
					registrationStatusMapperUtil.getStatusCode(RegistrationExceptionTypeCode.EXCEPTION));
			messageDTO.setInternalError(true);
			messageDTO.setIsValid(false);
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId,
					PlatformErrorMessages.PACKET_UPLOAD_FAILED.name() + ExceptionUtils.getStackTrace(e));
			messageDTO.setInternalError(Boolean.TRUE);
			description = "Internal error occured while processing for registrationId " + registrationId + "::"
					+ e.getMessage();
		} finally {
			registrationStatusService.updateRegistrationStatus(dto);
			String eventId = "";
			String eventName = "";
			String eventType = "";
			eventId = isTransactionSuccessful ? EventId.RPR_402.toString() : EventId.RPR_405.toString();
			eventName = eventId.equalsIgnoreCase(EventId.RPR_402.toString()) ? EventName.UPDATE.toString()
					: EventName.EXCEPTION.toString();
			eventType = eventId.equalsIgnoreCase(EventId.RPR_402.toString()) ? EventType.BUSINESS.toString()
					: EventType.SYSTEM.toString();

			auditLogRequestBuilder.createAuditRequestBuilder(description, eventId, eventName, eventType,
					this.registrationId, ApiName.AUDIT);

		}



		return messageDTO;
	}





	/**
	 * Scan file.
	 *
	 * @param inputStream            the input stream
	 * @return true, if successful
	 */
	private boolean scanFile(InputStream inputStream) {
		boolean isInputFileClean=false;
		try {
			isInputFileClean = virusScannerService.scanFile(inputStream);
			if (!isInputFileClean) {
				description = "Packet virus scan failed  in packet receiver for registrationId ::" + registrationId	+ PlatformErrorMessages.RPR_PUM_PACKET_VIRUS_SCAN_FAILED.getMessage();
				dto.setStatusCode(RegistrationExceptionTypeCode.PACKET_UPLOADER_FAILED.toString());
				dto.setStatusComment(StatusMessage.VIRUS_SCAN_FAILED);
				dto.setLatestTransactionStatusCode(registrationExceptionMapperUtil.getStatusCode(RegistrationExceptionTypeCode.VIRUS_SCAN_FAILED_EXCEPTION));
				regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),LoggerFileConstant.REGISTRATIONID.toString(), registrationId,PlatformErrorMessages.RPR_PUM_PACKET_VIRUS_SCAN_FAILED.getMessage());}
		} catch (VirusScannerException e) {

			description = "Virus scanner service failed ::" + registrationId;
			dto.setStatusCode(RegistrationExceptionTypeCode.PACKET_UPLOADER_FAILED.toString());
			dto.setStatusComment(StatusMessage.VIRUS_SCANNER_SERVICE_FAILED);
			dto.setLatestTransactionStatusCode(registrationExceptionMapperUtil.getStatusCode(RegistrationExceptionTypeCode.VIRUS_SCANNER_SERVICE_FAILED));
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),registrationId, PlatformErrorMessages.RPR_PUM_PACKET_VIRUS_SCANNER_SERVICE_FAILED.getMessage());

		}
		return isInputFileClean;
	}





	/**
	 * Validate hash code.
	 *
	 * @param inputStream the input stream
	 * @return true, if successful
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	private boolean validateHashCode(InputStream inputStream) throws IOException {
		boolean isValidHash=false;
		byte[] isbytearray = IOUtils.toByteArray(inputStream);
		byte[] hashSequence = HMACUtils.generateHash(isbytearray);
		byte[] packetHashSequenceFromEntity = hashSequence;//Todo: PacketHashSequesnce
		if (!(Arrays.equals(hashSequence, packetHashSequenceFromEntity))) {
			description = "The Registration Packet HashSequence is not equal as synced packet HashSequence"	+ registrationId;
			dto.setStatusCode(RegistrationExceptionTypeCode.PACKET_UPLOADER_FAILED.toString());
			dto.setStatusComment(StatusMessage.PACKET_SYNC_HASH_VALIDATION_FAILED);
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId, PlatformErrorMessages.RPR_PKR_PACKET_HASH_NOT_EQUALS_SYNCED_HASH.getMessage());

			return isValidHash;
		}else {

			isValidHash=true;
			return isValidHash;

		}
	}


	/**
	 * Uploadpacket.
	 *
	 * @param dto the dto
	 * @param decryptedData the decrypted data
	 * @param object the object
	 * @return the message DTO
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	private MessageDTO uploadpacket(InternalRegistrationStatusDto dto, InputStream decryptedData, MessageDTO object,SftpJschConnectionDto jschConnectionDto)
			throws IOException {

		object.setIsValid(false);
		registrationId = dto.getRegistrationId();
		hdfsAdapter.storePacket(registrationId, decryptedData);
		hdfsAdapter.unpackPacket(registrationId);

		if (hdfsAdapter.isPacketPresent(registrationId)) {

			if(packetArchiver.archivePacket(dto.getRegistrationId(), jschConnectionDto)) {

				if(fileManager.cleanUpFile(dto.getRegistrationId(), DirectoryPathDto.LANDING_ZONE, DirectoryPathDto.ARCHIVE_LOCATION, jschConnectionDto)) {

					dto.setStatusCode(RegistrationStatusCode.PACKET_UPLOADED_TO_FILESYSTEM.toString());
					dto.setStatusComment("Packet " + registrationId + " is uploaded in file system.");
					dto.setUpdatedBy(USER);
					object.setInternalError(false);
					object.setIsValid(true);
					object.setRid(registrationId);

					isTransactionSuccessful = true;
					description = " packet sent to DFS for registrationId " + registrationId;
					regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
							registrationId, PlatformErrorMessages.RPR_PUM_PACKET_DELETION_INFO.getMessage());
				}

			}

		}

		return object;
	}

	/**
	 * Get max retry count.
	 * 
	 * @return maxRetryCount
	 */
	public int getMaxRetryCount() {
		return maxRetryCount;
	}
}