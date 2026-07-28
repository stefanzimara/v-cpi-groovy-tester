import com.sap.gateway.ip.core.customdev.util.Message;
import groovy.json.*;
import groovy.util.*;
import groovy.xml.*;
import java.util.HashMap;
import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import groovy.json.JsonBuilder;
import java.text.SimpleDateFormat;
import java.sql.Timestamp;
import net.sf.json.JSON;
import net.sf.json.JSONSerializer;
import net.sf.json.xml.XMLSerializer;
import net.sf.json.JSONObject;
import net.sf.json.JSONArray;
import groovy.xml.MarkupBuilder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.apache.commons.lang3.StringEscapeUtils;

def Message processData(Message message)
{
    def xmlEnvelope = "";
    def xmlResponse = "";
    def debugInfoText = "";
    def props = message.getProperties();
    def picklistMappings = props.get("picklistMappings");
    def body = message.getBody(java.lang.String);
    try {
        if (body == ""){
            debugInfoText += "-----> body is empty" + System.lineSeparator();
            xmlEnvelope += createDefaultXMLResponse();
        }
        else {
            def jsonSlurper = new JsonSlurper();
            def sourceJSON = jsonSlurper.parseText(body);
            //debugInfoText += "-----> sourceJSON: " + System.lineSeparator();
            //debugInfoText += JsonOutput.prettyPrint(JsonOutput.toJson(sourceJSON)) + System.lineSeparator() + System.lineSeparator();
            def xmlContent = processPerPersonEntity(sourceJSON, picklistMappings, message, false);
            if (xmlContent == "") {
                xmlEnvelope += createDefaultXMLResponse();
            }
            else {
                xmlEnvelope += "<Records>";
                xmlEnvelope += xmlContent;
                xmlEnvelope += "</Records>";
            }
        }
        //-----------------------------------------------------------------------------
        // set response json to message body
        //-----------------------------------------------------------------------------
        message.setBody(xmlEnvelope);
        message.setProperty("IgnoreMessageForLog", "no");

    } catch (Exception e) {
        debugInfoText += "-----> Exception: " + e.getMessage() + System.lineSeparator();
        xmlEnvelope += createDefaultXMLResponse();
        xmlResponse = xmlEnvelope;
        message.setBody(xmlResponse);
        message.setProperty("IgnoreMessageForLog", "no");
    }
    // **************************** DEBUG LOGGING ****************************
    def debugLoggingEnabled = props.get("debugLoggingEnabled") ?: "";
    if (debugLoggingEnabled.equalsIgnoreCase("true") || debugLoggingEnabled.equalsIgnoreCase("yes"))
    {
        def processDebugLog = props.get("processDebugLog");
        processDebugLog += "incoming message body" + System.lineSeparator() + "body:" + System.lineSeparator() + System.lineSeparator() + message.getBody(java.lang.String) + System.lineSeparator() + System.lineSeparator() + "outgoing message body" + System.lineSeparator() + xmlEnvelope + System.lineSeparator();
        message.setProperty("processDebugLog", processDebugLog);
    }
    // **************************** DEBUG LOGGING ****************************
    def messageLog = messageLogFactory.getMessageLog(message);
    if(messageLog != null)
    {
        //messageLog.addAttachmentAsString("Payload Log", "incoming message body" + System.lineSeparator() + body + System.lineSeparator() + "outgoing message body" + System.lineSeparator() + xmlEnvelope, "text/plain");
    }

    return message;
}

def String processPerPersonEntity(Map jsonMap, String picklistMappings, Message message, boolean forceUpdate)
{
    def props = message.getProperties();
    def debugInfoText = "";
    def xmlResponse = "";
    def payloadData = "";
    def payComponentFilter = [];
    def payComponentFilterTemp = [];
    def forceCutOffDateEntry = "";
    def pastEmploymentPresent = false;
    def currentEmploymentPresent = false;
    def futureEmploymentPresent = false;
    try {

        def forceCutOffDateEntryTemp = props.get("ForceCutOffDateEntry");
        if (forceCutOffDateEntryTemp != "") {
            forceCutOffDateEntry = forceCutOffDateEntryTemp;
        }
        //-----------------------------------------------------------------------------
        // read out source values from SF EC and apply mapping for picklist values
        //-----------------------------------------------------------------------------
        def personIdExternal = jsonMap.PerPerson.personIdExternal; //always required
        message.setProperty("currentEmployeeNo", personIdExternal);
        //debugInfoText +=  System.lineSeparator() + System.lineSeparator() + "json data PerPerson is " + JsonOutput.prettyPrint(JsonOutput.toJson(jsonMap)) + System.lineSeparator() + System.lineSeparator();
        debugInfoText += "processPerPersonEntity processing:" + System.lineSeparator();
        debugInfoText += "personIdExternal is " + personIdExternal + System.lineSeparator();
        if (jsonMap == null) {
            // do not process
            debugInfoText += "payload content/JSON is null" + System.lineSeparator();
        }
        else {
            //Achtung: echte Mehrfachanstellung wird nicht unterstützt - es wird pro Mitarbeiter nur eine Exportzeile generiert
            //Contingent Workers mit mehreren sich nicht überschneidenden Anstellung (maximal eine aktiv) werden korrekt verarbeitet
            def empEmploymentTemp = convertToArrayList(jsonMap.PerPerson.employmentNav.EmpEmployment, message);
            empEmploymentTemp.each { entryEE ->
                //debugInfoText += "entryEE is " + JsonOutput.prettyPrint(JsonOutput.toJson(entryEE)) + System.lineSeparator();
                //debugInfoText += "entryEE class is " + entryEE.getClass() + System.lineSeparator();
                //debugInfoText += "entryEE.toJson class is " + JsonOutput.toJson(entryEE).getClass() + System.lineSeparator();
                //debugInfoText += "entryEE.toJson is "+ System.lineSeparator() + System.lineSeparator() + JsonOutput.toJson(entryEE) + System.lineSeparator() + System.lineSeparator();

                //entryEE = entryEE.value;
                def slurper = new JsonSlurper();
                def entryEESlurped = slurper.parseText(JsonOutput.prettyPrint(JsonOutput.toJson(entryEE)));
                //debugInfoText += "entryEESlurped class is " + entryEESlurped.getClass() + System.lineSeparator();

                if (entryEESlurped.keySet().contains("hiringNotCompleted") && entryEESlurped.hiringNotCompleted == true){
                    // this an hiringNotCompleted entry
                }
                else {
                    //def seniorityDate = entryEE.seniorityDate;
                    //def lastDateWorked = entryEE.lastDateWorked;
                    //def originalStartDate = entryEE.originalStartDate;
                    def employmentDate = formatDateYYYMMDD(entryEE.startDate);
                    def resignationDate = formatDateYYYMMDD(entryEE.endDate);
                    def lastDateWorkedDate = formatDateYYYMMDD(entryEE.lastDateWorked);
                    def lastModifiedDateTime = entryEE.lastModifiedDateTime;
                    debugInfoText += "employmentDate is " + employmentDate + System.lineSeparator();
                    debugInfoText += "resignationDate is " + resignationDate + System.lineSeparator();
                    debugInfoText += "lastDateWorkedDate is " + lastDateWorkedDate + System.lineSeparator();
                    debugInfoText += "lastModifiedDateTime is " + lastModifiedDateTime + System.lineSeparator();
                    try {
                        def jsonElemContent = convertToArrayList(entryEE, message);
                        jsonElemContent = homogenifyArrayList(jsonElemContent, message);
                        jsonElemContent.each{ entryTemp ->
                            entryTemp.each{ entryTemp2 ->
                                if (entryTemp2.key == "jobInfoNav") {
                                    entryTemp2.value.EmpJob = convertToArrayList(entryTemp2.value.EmpJob, message);
                                    // determine highest sequence number for each unique startDate
                                    def sequenceNumbers = [:];
                                    debugInfoText += "sequenceNumbers scanning: " + System.lineSeparator() + System.lineSeparator();
                                    entryTemp2.value.EmpJob.each{ entryEJ ->
                                        debugInfoText += "checking entry startDate: " + formatDateYYYMMDD(entryEJ.get("startDate")) + " seqNumber: " + entryEJ.get("seqNumber") + System.lineSeparator();
                                        if (sequenceNumbers[formatDateYYYMMDD(entryEJ.get("startDate"))]) {
                                            debugInfoText += "EmpJob seqNumber: " + entryEJ.get("seqNumber") + System.lineSeparator();
                                            debugInfoText += "sequenceNumbers seqNumber: " + sequenceNumbers[formatDateYYYMMDD(entryEJ.get("startDate"))] + System.lineSeparator();
                                            if (sequenceNumbers[formatDateYYYMMDD(entryEJ.get("startDate"))] < entryEJ.get("seqNumber")) {
                                                debugInfoText += "new higher seqNumber detected: " + entryEJ.get("seqNumber") + System.lineSeparator();
                                                sequenceNumbers.remove(formatDateYYYMMDD(entryEJ.get("startDate")));
                                                sequenceNumbers[formatDateYYYMMDD(entryEJ.get("startDate"))] = entryEJ.get("seqNumber");
                                                debugInfoText += "new entry added with startDate: " + formatDateYYYMMDD(entryEJ.get("startDate")) + " seqNumber: " + entryEJ.get("seqNumber") + System.lineSeparator();
                                            }
                                        }
                                        else {
                                            sequenceNumbers[formatDateYYYMMDD(entryEJ.get("startDate"))] = entryEJ.get("seqNumber");
                                            debugInfoText += "new entry added with startDate: " + formatDateYYYMMDD(entryEJ.get("startDate")) + " seqNumber: " + entryEJ.get("seqNumber") + System.lineSeparator();
                                        }
                                    }
                                    debugInfoText +=  System.lineSeparator() +  System.lineSeparator() + "sequenceNumbers is " + sequenceNumbers + System.lineSeparator();
                                    debugInfoText += "sequenceNumbers completed" + System.lineSeparator()+ System.lineSeparator()+ System.lineSeparator();
                                    entryTemp2.value.EmpJob.each{ entryEJ ->
                                        debugInfoText += "processing entryEJ: " + entryEJ.toString() + System.lineSeparator();
                                        debugInfoText += "processing entryEJ with startDate " + formatDateYYYMMDD(entryEJ.startDate) + " and seqNumber " + entryEJ.seqNumber + System.lineSeparator();
                                        if (sequenceNumbers[formatDateYYYMMDD(entryEJ.startDate)] == entryEJ.seqNumber) {

                                            // check if this time slice within desired future change window
                                            //-> no terminated employees
                                            //-> current employeed
                                            //-> future hires within the next 33 days
                                            def dateNow = new Date();
                                            def maxFutureHireDate = dateNow.clone();
                                            def maxPastTerminationDate = dateNow.clone();

                                            def cal = Calendar.getInstance();
                                            cal.setTime(maxFutureHireDate);
                                            cal.add(cal.DAY_OF_MONTH,33);
                                            maxFutureHireDate = cal.getTime();

                                            cal = Calendar.getInstance();
                                            cal.setTime(maxPastTerminationDate);
                                            cal.add(cal.DAY_OF_MONTH,-30);
                                            maxPastTerminationDate = cal.getTime();

                                            def startDateEmpJob = Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", entryEJ.startDate);
                                            def endDateEmpJob = null;
                                            if (entryEJ.endDate == ""){
                                                endDateEmpJob = Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", "9999-12-31T00:00:00.000");
                                            }
                                            else {
                                                endDateEmpJob = Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", entryEJ.endDate);
                                            }
                                            debugInfoText += "startDateEmpJob " + startDateEmpJob.format("yyyyMMdd") + System.lineSeparator();
                                            debugInfoText += "endDateEmpJob " + endDateEmpJob.format("yyyyMMdd") + System.lineSeparator();
                                            debugInfoText += "event " + entryEJ.event + System.lineSeparator();

                                            def employmentDateDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", entryEE.startDate);
                                            def terminationDateDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", "9999-12-31T00:00:00.000");
                                            if (entryEE.endDate != "") {
                                                terminationDateDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", entryEE.endDate);
                                            }
                                            debugInfoText += "Evaluating validity of EmpEmployment/EmpJob" + System.lineSeparator();
                                            def processJobEntry = false;

                                            def casePastEmployment = false;
                                            def caseCurrentEmployment = false;
                                            def caseFutureEmployment = false;

                                            if(entryEJ.event == "449") {
                                                // termination event/time slice
                                                debugInfoText += "-> termination time slice -> do NOT process" + System.lineSeparator();
                                            }
                                            else if ((endDateEmpJob.compareTo(dateNow) < 0) && (endDateEmpJob.compareTo(maxPastTerminationDate) < 0)){
                                                // terminated employee or employee with past jobInfo older than 30 days
                                                debugInfoText += "-> terminated employee or employee with past jobInfo older than 30 days -> do NOT process" + System.lineSeparator();
                                            }
                                            else if ((endDateEmpJob.compareTo(dateNow) < 0) && (endDateEmpJob.compareTo(maxPastTerminationDate) >= 0)){
                                                // terminated employee or employee with past jobInfo within last 30 days
                                                debugInfoText += "-> terminated employee or employee with past jobInfo within the last 30 days -> process" + System.lineSeparator();
                                                processJobEntry = true;
                                                casePastEmployment = true;
                                                pastEmploymentPresent = true;
                                            }
                                            else if (startDateEmpJob.compareTo(dateNow) <= 0 &&  endDateEmpJob.compareTo(dateNow) >= 0){
                                                // current employeed
                                                debugInfoText += "-> current employeed employee -> process" + System.lineSeparator();
                                                processJobEntry = true;
                                                caseCurrentEmployment = true;
                                                currentEmploymentPresent = true;
                                            }
                                            else if (startDateEmpJob.compareTo(dateNow) >= 0 && startDateEmpJob.compareTo(maxFutureHireDate) < 0){
                                                // future hires within the next 33 days
                                                debugInfoText += "-> future hires within the next 33 days -> process" + System.lineSeparator();
                                                processJobEntry = true;
                                                caseFutureEmployment = true;
                                                futureEmploymentPresent = true;
                                            }
                                            else {
                                                debugInfoText += "-> other case not covered -> do NOT process" + System.lineSeparator();
                                            }
                                            if (processJobEntry){
                                                // export only valid or future data
                                                net.sf.json.JSONObject tempJsonObjectJob = new JSONObject(entryEJ);
                                                String jsonJob = tempJsonObjectJob.toString();
                                                jsonJob = JsonOutput.prettyPrint(jsonJob);
                                                debugInfoText += "generating target xml " + System.lineSeparator() + System.lineSeparator();
                                                String xml = "";
                                                try {
                                                    def PersNr = "";
                                                    def BirthDate = "";
                                                    def CostCenter = "";
                                                    def Manager = "";
                                                    def StartDate = "";
                                                    def EndDate = "";
                                                    def Company = "";
                                                    def LocationCode = "";
                                                    def DepartmentCode = "";
                                                    def DepartmentName = "";
                                                    def DepartmentNameShort = "";
                                                    def Lastname = "";
                                                    def Firstname = "";
                                                    def Gender = "";
                                                    def NetworkAccess = "";
                                                    def Language = "";
                                                    def PersType = "";
                                                    def EmployeeType = "";
                                                    def JobTitle = "";
                                                    def AHVNummer = "";
                                                    def FirstnameIT = "";
                                                    def LastnameIT = "";

                                                    PersNr = personIdExternal;

                                                    PersNr = "000000000000" + PersNr;
                                                    PersNr = PersNr[-8..-1];
                                                    debugInfoText += "PersNr " + PersNr + System.lineSeparator();
                                                    BirthDate = formatDateYYYMMDD(jsonMap.PerPerson.dateOfBirth);
                                                    debugInfoText += "BirthDate " + BirthDate + System.lineSeparator();
                                                    CostCenter = entryEJ.costCenter;
                                                    debugInfoText += "CostCenter " + CostCenter + System.lineSeparator();
                                                    Manager = entryEJ.managerId;
                                                    if (Manager == null) {
                                                        Manager = "";
                                                    }
                                                    else if (Manager == "NO_MANAGER") {
                                                        Manager = "";
                                                    }
                                                    else {
                                                        Manager = "000000000000" + Manager;
                                                        Manager = Manager[-8..-1];
                                                    }
                                                    debugInfoText += "Manager " + Manager + System.lineSeparator();
                                                    StartDate = employmentDate;
                                                    debugInfoText += "StartDate " + StartDate + System.lineSeparator();

                                                    if (lastDateWorkedDate == "") {
                                                        EndDate = resignationDate;
                                                    } else {
                                                        EndDate = lastDateWorkedDate;
                                                    }
                                                    debugInfoText += "EndDate " + EndDate + System.lineSeparator();
                                                    Company = entryEJ.company;
                                                    if (Company == null) {
                                                        Company = "";
                                                    }
                                                    debugInfoText += "Company " + Company + System.lineSeparator();
                                                    LocationCode = entryEJ.location;
                                                    if (LocationCode == null) {
                                                        LocationCode = "";
                                                    }
                                                    debugInfoText += "LocationCode " + LocationCode + System.lineSeparator();
                                                    DepartmentCode = entryEJ.department;
                                                    if (DepartmentCode == null) {
                                                        DepartmentCode = "";
                                                    }
                                                    debugInfoText += "DepartmentCode " + DepartmentCode + System.lineSeparator();
                                                    DepartmentName = entryEJ.departmentNav;
                                                    if (DepartmentName == null || DepartmentName.getClass() == java.lang.String) {
                                                        DepartmentName = "";
                                                    } else {
                                                        DepartmentName = entryEJ.departmentNav.FODepartment.name;
                                                    }
                                                    if (DepartmentName == null) {
                                                        DepartmentName = "";
                                                    }
                                                    debugInfoText += "DepartmentName " + DepartmentName + System.lineSeparator();
                                                    DepartmentNameShort = entryEJ.departmentNav;
                                                    if (DepartmentNameShort == null ||entryEJ.departmentNav.getClass() == java.lang.String) {
                                                        DepartmentNameShort = "";
                                                    } else {
                                                        DepartmentNameShort = entryEJ.departmentNav.FODepartment.cust_apothekenkrzl;
                                                    }
                                                    if (DepartmentNameShort == null) {
                                                        DepartmentNameShort = "";
                                                    }
                                                    debugInfoText += "DepartmentNameShort " + DepartmentNameShort + System.lineSeparator();
                                                    NetworkAccess = entryEJ.customString13Nav;
                                                    if (NetworkAccess == null || NetworkAccess.getClass() == java.lang.String) {
                                                        NetworkAccess = "";
                                                    } else {
                                                        NetworkAccess = entryEJ.customString13Nav.PicklistOption.externalCode;
                                                    }
                                                    if (NetworkAccess == null) {
                                                        NetworkAccess = "";
                                                    }
                                                    debugInfoText += "NetworkAccess " + NetworkAccess + System.lineSeparator();
                                                    if (entryEJ.employeeClassNav != null && entryEJ.employeeClassNav.getClass() != java.lang.String){
                                                        if (entryEJ.employeeClassNav.PicklistOption != null && entryEJ.employeeClassNav.PicklistOption.getClass() != java.lang.String){
                                                            PersType = entryEJ.employeeClassNav.PicklistOption.externalCode;
                                                        }
                                                    }
                                                    if (PersType == null) {
                                                        PersType = "";
                                                    }
                                                    debugInfoText += "PersType " + PersType + System.lineSeparator();
                                                    if (entryEJ.customString9Nav != null  && entryEJ.customString9Nav.getClass() != java.lang.String){
                                                        if (entryEJ.customString9Nav.PicklistOption != null && entryEJ.customString9Nav.PicklistOption.getClass() != java.lang.String){
                                                            EmployeeType = entryEJ.customString9Nav.PicklistOption.externalCode;
                                                        }
                                                    }
                                                    if (EmployeeType == null) {
                                                        EmployeeType = "";
                                                    }
                                                    debugInfoText += "EmployeeType " + EmployeeType + System.lineSeparator();
                                                    if (entryEJ.positionNav != null  && entryEJ.positionNav.getClass() != java.lang.String){
                                                        if (entryEJ.positionNav.Position != null && entryEJ.positionNav.Position.getClass() != java.lang.String){
                                                            JobTitle = entryEJ.positionNav.Position.externalName_defaultValue;
                                                        }
                                                    }
                                                    if (JobTitle == null) {
                                                        JobTitle = "";
                                                    }
                                                    debugInfoText += "JobTitle " + JobTitle + System.lineSeparator() + System.lineSeparator();

                                                    if (entryEE.userNav != null && entryEE.userNav.getClass() != java.lang.String){
                                                        if (entryEE.userNav.User != null && entryEE.userNav.User.getClass() != java.lang.String){
                                                            if (entryEE.userNav.User.externalCodeOfcust_HRC_IT0036Nav != null && entryEE.userNav.User.externalCodeOfcust_HRC_IT0036Nav.getClass() != java.lang.String){
                                                                def temp_HRC_IT0036Nav = convertToArrayList(entryEE.userNav.User.externalCodeOfcust_HRC_IT0036Nav.cust_HRC_IT0036, message);
                                                                temp_HRC_IT0036Nav.each{ it0036Entry ->
                                                                    def it0036EntrySlurped = slurper.parseText(JsonOutput.prettyPrint(JsonOutput.toJson(it0036Entry)));
                                                                    debugInfoText += "it0036EntrySlurped is " + it0036EntrySlurped + System.lineSeparator();
                                                                    try {
                                                                        def ahvTemp = it0036EntrySlurped.cust_NAHVN;
                                                                        if (AHVNummer == ""){
                                                                            AHVNummer = ahvTemp;
                                                                        }
                                                                        def startDateIT0036 = Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", it0036EntrySlurped.effectiveStartDate);
                                                                        def endDateIT0036 = Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", it0036EntrySlurped.effectiveEndDate);
                                                                        debugInfoText += "processing externalCodeOfcust_HRC_IT0036Nav with startDate " + startDateIT0036.format("yyyyMMdd") + " enddate " + endDateIT0036.format("yyyyMMdd") + " and AHV " + ahvTemp +  System.lineSeparator();
                                                                        // search for correct data slice corresponding to EmpJob
                                                                        if ( ((startDateEmpJob <= startDateIT0036) && (startDateIT0036 <= endDateEmpJob)) || ((startDateEmpJob <= endDateIT0036) && (endDateIT0036 <= endDateEmpJob)) ) {
                                                                            AHVNummer = it0036EntrySlurped.cust_NAHVN;
                                                                        }
                                                                    } catch (Exception et){}
                                                                    if (AHVNummer == null) {
                                                                        AHVNummer = "";
                                                                    }
                                                                    debugInfoText += "AHVNummer " + AHVNummer + System.lineSeparator() + System.lineSeparator();
                                                                }
                                                            }
                                                        }
                                                    }
                                                    if (jsonMap.PerPerson.personalInfoNav != null && jsonMap.PerPerson.personalInfoNav.getClass() != java.lang.String){
                                                        if (jsonMap.PerPerson.personalInfoNav.PerPersonal != null && jsonMap.PerPerson.personalInfoNav.PerPersonal.getClass() != java.lang.String){
                                                            def tempPerPersonal = convertToArrayList(jsonMap.PerPerson.personalInfoNav.PerPersonal, message);
                                                            tempPerPersonal.each{ perpersonalEntry ->
                                                                debugInfoText += "perpersonalEntry is " + perpersonalEntry + System.lineSeparator();
                                                                def perpersonalEntrySlurped = slurper.parseText(JsonOutput.prettyPrint(JsonOutput.toJson(perpersonalEntry)));
                                                                debugInfoText += "perpersonalEntrySlurped is " + perpersonalEntrySlurped + System.lineSeparator();
                                                                def LastnameTemp = perpersonalEntrySlurped.lastName?.trim();
                                                                def FirstnameTemp = perpersonalEntrySlurped.firstName?.trim();
                                                                def GenderTemp = perpersonalEntrySlurped.gender;
                                                                def LanguageTemp = perpersonalEntrySlurped.nativePreferredLangNav.PicklistOption.externalCode;
                                                                def FirstnameITTemp = perpersonalEntrySlurped.preferredName?.trim();
                                                                def LastnameITTemp = perpersonalEntrySlurped.customString1?.trim();
                                                                if (Lastname == ""){
                                                                    Lastname = LastnameTemp;
                                                                }
                                                                if (Firstname == ""){
                                                                    Firstname = FirstnameTemp;
                                                                }
                                                                if (Gender == ""){
                                                                    Gender = GenderTemp;
                                                                }
                                                                if (Language == ""){
                                                                    Language = LanguageTemp;
                                                                }
                                                                if (FirstnameIT == ""){
                                                                    FirstnameIT = FirstnameITTemp;
                                                                }
                                                                if (LastnameIT == ""){
                                                                    LastnameIT = LastnameITTemp;
                                                                }
                                                                def startDatePerPersonal = Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", perpersonalEntrySlurped.startDate);
                                                                def endDatePerPersonal = Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", perpersonalEntrySlurped.endDate);
                                                                // search for correct data slice corresponding to EmpJob

                                                                def today = new Date()

                                                                //def dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS"
                                                                //def today = Date.parse(dateFormat, "2025-01-10T00:00:00.000")

                                                                debugInfoText += "processing PerPersonal with startDate " + startDatePerPersonal.format("yyyyMMdd") + System.lineSeparator();

                                                                if (startDatePerPersonal <= today && today <= endDatePerPersonal){

                                                                    if ( ((startDateEmpJob <= startDatePerPersonal) && (startDatePerPersonal <= endDateEmpJob)) || ((startDateEmpJob <= endDatePerPersonal) && (endDatePerPersonal <= endDateEmpJob)) ) {
                                                                        //Lastname += perpersonalEntrySlurped.lastName
                                                                        Lastname = perpersonalEntrySlurped.lastName?.trim();
                                                                        if (Lastname == null) {
                                                                            Lastname = "";
                                                                        }
                                                                        debugInfoText += "Lastname " + Lastname + System.lineSeparator();
                                                                        Firstname = perpersonalEntrySlurped.firstName?.trim();
                                                                        if (Firstname == null) {
                                                                            Firstname = "";
                                                                        }
                                                                        debugInfoText += "Firstname " + Firstname + System.lineSeparator();
                                                                        Gender = perpersonalEntrySlurped.gender;
                                                                        if (Gender == null) {
                                                                            Gender = "";
                                                                        }
                                                                        debugInfoText += "Gender " + Gender + System.lineSeparator();
                                                                        Language = perpersonalEntrySlurped.nativePreferredLangNav.PicklistOption.externalCode;
                                                                        if (Language == null) {
                                                                            Language = "";
                                                                        }
                                                                        debugInfoText += "Language " + Language + System.lineSeparator();
                                                                        FirstnameIT = perpersonalEntrySlurped.preferredName?.trim();
                                                                        if (FirstnameIT == null) {
                                                                            FirstnameIT = "";
                                                                        }
                                                                        debugInfoText += "FirstnameIT " + FirstnameIT + System.lineSeparator();
                                                                        LastnameIT = perpersonalEntrySlurped.customString1?.trim();
                                                                        if (LastnameIT == null) {
                                                                            LastnameIT = "";
                                                                        }
                                                                        debugInfoText += "LastnameIT " + LastnameIT + System.lineSeparator() + System.lineSeparator();
                                                                    }

                                                                }

                                                            }
                                                        }
                                                    }
                                                    debugInfoText += "creating xml record" + System.lineSeparator() + System.lineSeparator();
                                                    xml += "<Record>";
                                                    xml += "<PersNr>";
                                                    xml += StringEscapeUtils.escapeXml11(PersNr);
                                                    xml += "</PersNr>";
                                                    xml += "<BirthDate>";
                                                    xml += StringEscapeUtils.escapeXml11(BirthDate);
                                                    xml += "</BirthDate>";
                                                    xml += "<CostCenter>";
                                                    xml += StringEscapeUtils.escapeXml11(CostCenter);
                                                    xml += "</CostCenter>";
                                                    xml += "<Manager>";
                                                    xml += StringEscapeUtils.escapeXml11(Manager);
                                                    xml += "</Manager>";
                                                    xml += "<StartDate>";
                                                    xml += StringEscapeUtils.escapeXml11(StartDate);
                                                    xml += "</StartDate>";
                                                    xml += "<EndDate>";
                                                    xml += StringEscapeUtils.escapeXml11(EndDate);
                                                    xml += "</EndDate>";
                                                    xml += "<Company>";
                                                    xml += StringEscapeUtils.escapeXml11(Company);
                                                    xml += "</Company>";
                                                    xml += "<LocationCode>";
                                                    xml += StringEscapeUtils.escapeXml11(LocationCode);
                                                    xml += "</LocationCode>";
                                                    xml += "<DepartmentCode>";
                                                    xml += StringEscapeUtils.escapeXml11(DepartmentCode);
                                                    xml += "</DepartmentCode>";
                                                    xml += "<DepartmentName>";
                                                    xml += StringEscapeUtils.escapeXml11(DepartmentName);
                                                    xml += "</DepartmentName>";
                                                    xml += "<DepartmentNameShort>";
                                                    xml += StringEscapeUtils.escapeXml11(DepartmentNameShort);
                                                    xml += "</DepartmentNameShort>";
                                                    xml += "<Lastname>";
                                                    xml += StringEscapeUtils.escapeXml11(Lastname);
                                                    xml += "</Lastname>";
                                                    xml += "<Firstname>";
                                                    xml += StringEscapeUtils.escapeXml11(Firstname);
                                                    xml += "</Firstname>";
                                                    xml += "<Gender>";
                                                    xml += StringEscapeUtils.escapeXml11(Gender);
                                                    xml += "</Gender>";
                                                    xml += "<NetworkAccess>";
                                                    xml += StringEscapeUtils.escapeXml11(NetworkAccess);
                                                    xml += "</NetworkAccess>";
                                                    xml += "<Language>";
                                                    xml += StringEscapeUtils.escapeXml11(Language);
                                                    xml += "</Language>";
                                                    xml += "<PersType>";
                                                    xml += StringEscapeUtils.escapeXml11(PersType);
                                                    xml += "</PersType>";
                                                    xml += "<EmployeeType>";
                                                    xml += StringEscapeUtils.escapeXml11(EmployeeType);
                                                    xml += "</EmployeeType>";
                                                    xml += "<JobTitle>";
                                                    xml += StringEscapeUtils.escapeXml11(JobTitle);
                                                    xml += "</JobTitle>";
                                                    xml += "<AHVNummer>";
                                                    xml += StringEscapeUtils.escapeXml11(AHVNummer);
                                                    xml += "</AHVNummer>";
                                                    xml += "<FirstnameIT>";
                                                    xml += StringEscapeUtils.escapeXml11(FirstnameIT);
                                                    xml += "</FirstnameIT>";
                                                    xml += "<LastnameIT>";
                                                    xml += StringEscapeUtils.escapeXml11(LastnameIT);
                                                    xml += "</LastnameIT>";
                                                    xml += "</Record>";


                                                    debugInfoText += "casePastEmployment " + casePastEmployment + System.lineSeparator();
                                                    debugInfoText += "caseCurrentEmployment " + caseCurrentEmployment + System.lineSeparator();
                                                    debugInfoText += "caseFutureEmployment " + caseFutureEmployment + System.lineSeparator();
                                                    debugInfoText += "pastEmploymentPresent " + pastEmploymentPresent + System.lineSeparator();
                                                    debugInfoText += "currentEmploymentPresent " + currentEmploymentPresent + System.lineSeparator();
                                                    debugInfoText += "futureEmploymentPresent " + futureEmploymentPresent + System.lineSeparator();


                                                    if (casePastEmployment && !currentEmploymentPresent && !futureEmploymentPresent) { // only to be exported if no current and future employment present
                                                        debugInfoText += "xml record" + System.lineSeparator() + xml + System.lineSeparator() + System.lineSeparator();
                                                        xmlResponse = xml;
                                                    }
                                                    else if (caseCurrentEmployment) { //has always highest priority
                                                        debugInfoText += "xml record" + System.lineSeparator() + xml + System.lineSeparator() + System.lineSeparator();
                                                        xmlResponse = xml;
                                                    }
                                                    else if (caseFutureEmployment && !currentEmploymentPresent) { // only to be exported if no current employment present
                                                        debugInfoText += "xml record" + System.lineSeparator() + xml + System.lineSeparator() + System.lineSeparator();
                                                        xmlResponse = xml;
                                                    }
                                                    else {
                                                        debugInfoText += "xml record not generated due to lower priority" + System.lineSeparator() + System.lineSeparator();
                                                    }

                                                    xml = "";
                                                } catch (Exception e) {
                                                    debugInfoText += "Exception on generating xml target structure: " + e.getMessage() + System.lineSeparator();
                                                    debugInfoText += "xml (so far): " + xml + System.lineSeparator();
                                                    xml = "";
                                                }
                                            }
                                            else {
                                                debugInfoText += "skipping proccessing for entryEJ with startDate " + formatDateYYYMMDD(entryEJ.startDate) + " seqNumber " + entryEJ.seqNumber + System.lineSeparator() + System.lineSeparator() + System.lineSeparator();
                                            }
                                        }
                                        else {
                                            debugInfoText += "skipping proccessing for entryEJ with startDate " + formatDateYYYMMDD(entryEJ.startDate) + " seqNumber " + entryEJ.seqNumber + System.lineSeparator() + System.lineSeparator() + System.lineSeparator();
                                        }
                                        xml = "";
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        debugInfoText += "Exception on processing empemployment: " + e.getMessage() + System.lineSeparator();
                        xmlResponse = "";
                    }
                }
            }
        }
    } catch (Exception e) {
        debugInfoText += "-----> Exception: " + e.getMessage() + System.lineSeparator();
        xmlResponse = "";
    }
    debugInfoText += System.lineSeparator() + "-----> xmlResponse: " + xmlResponse + System.lineSeparator();
    // **************************** DEBUG LOGGING ****************************
    def debugLoggingEnabled = props.get("debugLoggingEnabled") ?: "";
    if (debugLoggingEnabled.equalsIgnoreCase("true") || debugLoggingEnabled.equalsIgnoreCase("yes"))
    {
        def processDebugLog = props.get("processDebugLog");
        processDebugLog += "processPerPersonEntity" + System.lineSeparator() + debugInfoText + System.lineSeparator();
        message.setProperty("processDebugLog", processDebugLog);
    }
    // **************************** DEBUG LOGGING ****************************

    // Achtung: hier bewusst KEINE Default-Huelle erzeugen. processData prueft auf
    // Leerstring und ruft dann createDefaultXMLResponse() auf - sonst entsteht
    // ein doppelt geschachteltes <Records><Records>...
    debugInfoText += "xmlResponse is " + System.lineSeparator() + System.lineSeparator() + xmlResponse + System.lineSeparator();

    return xmlResponse;
}

def String createDefaultXMLResponse() {
    def xmlEnvelope = "";
    xmlEnvelope += "<Records>";
    xmlEnvelope += "<Record>";
    xmlEnvelope += "<PersNr>";
    xmlEnvelope += "</PersNr>";
    xmlEnvelope += "<BirthDate>";
    xmlEnvelope += "</BirthDate>";
    xmlEnvelope += "<CostCenter>";
    xmlEnvelope += "</CostCenter>";
    xmlEnvelope += "<Manager>";
    xmlEnvelope += "</Manager>";
    xmlEnvelope += "<StartDate>";
    xmlEnvelope += "</StartDate>";
    xmlEnvelope += "<EndDate>";
    xmlEnvelope += "</EndDate>";
    xmlEnvelope += "<Company>";
    xmlEnvelope += "</Company>";
    xmlEnvelope += "<LocationCode>";
    xmlEnvelope += "</LocationCode>";
    xmlEnvelope += "<DepartmentCode>";
    xmlEnvelope += "</DepartmentCode>";
    xmlEnvelope += "<DepartmentName>";
    xmlEnvelope += "</DepartmentName>";
    xmlEnvelope += "<DepartmentNameShort>";
    xmlEnvelope += "</DepartmentNameShort>";
    xmlEnvelope += "<Lastname>";
    xmlEnvelope += "</Lastname>";
    xmlEnvelope += "<Firstname>";
    xmlEnvelope += "</Firstname>";
    xmlEnvelope += "<Gender>";
    xmlEnvelope += "</Gender>";
    xmlEnvelope += "<NetworkAccess>";
    xmlEnvelope += "</NetworkAccess>";
    xmlEnvelope += "<Language>";
    xmlEnvelope += "</Language>";
    xmlEnvelope += "<PersType>";
    xmlEnvelope += "</PersType>";
    xmlEnvelope += "<EmployeeType>";
    xmlEnvelope += "</EmployeeType>";
    xmlEnvelope += "<JobTitle>";
    xmlEnvelope += "</JobTitle>";
    xmlEnvelope += "<AHVNummer>";
    xmlEnvelope += "</AHVNummer>";
    xmlEnvelope += "<FirstnameIT>";
    xmlEnvelope += "</FirstnameIT>";
    xmlEnvelope += "<LastnameIT>";
    xmlEnvelope += "</LastnameIT>";
    xmlEnvelope += "</Record>";
    xmlEnvelope += "</Records>";
    return xmlEnvelope;
}

// helper functions
def String formatDateYYYMMDD(String tempDate)
{
    if (tempDate == null) {
        return "";
    }
    else if (tempDate.equals("")) {
        return "";
    }
    else {
        def formattedDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", tempDate).format("yyyyMMdd");
        if (formattedDate.equals("99991231")) {
            formattedDate = "";
        }
        return formattedDate;
    }
}

def String getCompanyPerDate(Map jsonMap, String targetDate, String legalEntityMappings, Message message)
{
    def debugInfoText = "";
    def company = "undefined";
    if (targetDate == "")
    {
        debugInfoText += "targetDate is empty" + System.lineSeparator();
        def dateNow = new Date();
        targetDate = dateNow.format("yyyy-MM-dd'T'HH:mm:ss.sss");
    }
    def personIdExternal = jsonMap.personIdExternal; //always required
    def jsonElem = null;
    try {
        jsonElem = jsonMap.jobInfoNav.EmpJob;
    } catch (Exception e) {
        debugInfoText += "-----> Exception: " + e.getMessage() + System.lineSeparator();
        jsonElem = null;
    }
    if (jsonElem == null) {
        // do not process
        return company;
    }
    jsonElem = convertToArrayList(jsonElem, message)
    jsonElem = homogenifyArrayList(jsonElem, message);
    jsonElem.each { entryC ->
        //entryC.eachWithIndex{entry, i -> debugInfoText += "$i $entry.key: $entry" + System.lineSeparator()}
        //debugInfoText += "-----> entryC.startDate " + entryC.startDate + System.lineSeparator();
        //debugInfoText += "-----> entryC.endDate " + entryC.endDate + System.lineSeparator();
        //debugInfoText += "-----> entryC.company " + entryC.company + System.lineSeparator();
        //debugInfoText += "-----> targetDate " + targetDate + System.lineSeparator();
        def startDate = entryC.startDate;
        def endDate = entryC.endDate;
        //debugInfoText += "-----> startDate comparison result " + (Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", startDate) <= Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", targetDate)).toString() + System.lineSeparator();
        //debugInfoText += "-----> endDate comparison result " + (Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", targetDate) <= Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", endDate)).toString() + System.lineSeparator();
        if ((Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", startDate) <= Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", targetDate)) && (Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", targetDate) <= Date.parse("yyyy-MM-dd'T'HH:mm:ss.sss", endDate)))
        {
            company = entryC.company;

        }
        debugInfoText += "-----> company result: " + company + System.lineSeparator();
    }
    // **************************** DEBUG LOGGING ****************************
    def props = message.getProperties();
    def debugLoggingEnabled = props.get("debugLoggingEnabled") ?: "";
    if (debugLoggingEnabled.equalsIgnoreCase("true") || debugLoggingEnabled.equalsIgnoreCase("yes"))
    {
        def processDebugLog = props.get("processDebugLog");
        processDebugLog += "getCompanyPerDate" + System.lineSeparator() + debugInfoText + System.lineSeparator();
        //message.setProperty("processDebugLog", processDebugLog);
    }
    // **************************** DEBUG LOGGING ****************************

    /*
    def dateLog = Date.parse("E MMM dd HH:mm:ss z yyyy", dateNow as String).format("yyyy-MM-dd_HHmmss.s")

    def messageLog = messageLogFactory.getMessageLog(message);
    if(messageLog != null)
    {
    messageLog.addAttachmentAsString("DEBUG Log " + dateLog + "", "debug message for getCompanyPerDate:" + System.lineSeparator() + debugInfoText + System.lineSeparator(), "text/plain");
    }
     */
    return company;
}
def String processPicklistMapping(String targetFieldname, String value, String picklistMappings, Message message)
{
    def returnValue = value;

    def debugInfoText = "";
    def dateNow = new Date();
    def dateLog = Date.parse("E MMM dd HH:mm:ss z yyyy", dateNow as String).format("yyyy-MM-dd_HHmmss.s")
    debugInfoText += "-----> targetFieldname input: " + targetFieldname + System.lineSeparator();
    debugInfoText += "-----> value input: " + value + System.lineSeparator();

    def slurper = new JsonSlurper();
    def plMappings = slurper.parseText(picklistMappings);

    plMappings.picklistMappings.eachWithIndex{entry, i -> debugInfoText += "$i $entry.fieldname" + System.lineSeparator()}
    plMappings.picklistMappings.each { entryPL ->
        if (entryPL.fieldname == targetFieldname)
        {
            debugInfoText += "-----> picklistMappings found: " + entryPL.fieldname + System.lineSeparator();
            entryPL.values.each { item ->
                if (item.source == value)
                {
                    debugInfoText += "-----> picklistMappings value conversion found: " + item.source + System.lineSeparator();
                    debugInfoText += "-----> picklistMappings value target: " + item.target + System.lineSeparator();
                    returnValue = item.target;
                }
            }
        }
    }
    debugInfoText += "-----> returnValue output: " + returnValue + System.lineSeparator();

    // **************************** DEBUG LOGGING ****************************
    def props = message.getProperties();
    def debugLoggingEnabled = props.get("debugLoggingEnabled") ?: "";
    if (debugLoggingEnabled.equalsIgnoreCase("true") || debugLoggingEnabled.equalsIgnoreCase("yes"))
    {
        def processDebugLog = props.get("processDebugLog");
        processDebugLog += "processPicklistMapping" + System.lineSeparator() + debugInfoText + System.lineSeparator();
        //message.setProperty("processDebugLog", processDebugLog);
    }
    // **************************** DEBUG LOGGING ****************************
    /*
    def messageLog = messageLogFactory.getMessageLog(message);
    if(messageLog != null)
    {
    messageLog.addAttachmentAsString("DEBUG Log " + dateLog + "", "debug message for processPicklistMapping:" + System.lineSeparator() + debugInfoText + System.lineSeparator(), "text/plain");
    }
     */

    //if no mapping is present return unchanged value
    return returnValue;
}
def ArrayList convertToArrayList(java.lang.Object jsonElem, Message message)
{
    def debugInfoText = "";

    if (jsonElem == null)
    {
        // do not process
        debugInfoText += "-----> jsonElem is null" + System.lineSeparator();
    }
    else if (jsonElem.getClass().toString().equals("class java.util.ArrayList"))
    {
        // process jsonElem.value array directly
        //debugInfoText += "-----> ArrayList class detected " + jsonElem.getClass().toString() + System.lineSeparator();
    }
    else if (jsonElem.getClass().toString().equals("class groovy.json.internal.LazyMap"))
    {
        // convert jsonElem LazyMap into array and process
        //debugInfoText += "-----> LazyMap class detected " + jsonElem.getClass().toString() + System.lineSeparator();
        jsonElem = [jsonElem];
    }
    else
    {
        // convert jsonElem into array and process
        debugInfoText += "-----> other class detected " + jsonElem.getClass().toString() + System.lineSeparator();
        jsonElem = [jsonElem];
    }
    debugInfoText += "-----> jsonElem returned: " + jsonElem + System.lineSeparator();

    // **************************** DEBUG LOGGING ****************************
    def props = message.getProperties();
    def debugLoggingEnabled = props.get("debugLoggingEnabled") ?: "";
    if (debugLoggingEnabled.equalsIgnoreCase("true") || debugLoggingEnabled.equalsIgnoreCase("yes"))
    {
        def processDebugLog = props.get("processDebugLog");
        processDebugLog += "convertToArrayList" + System.lineSeparator() + debugInfoText + System.lineSeparator();
        //message.setProperty("processDebugLog", processDebugLog);
    }
    // **************************** DEBUG LOGGING ****************************

    return jsonElem;
}
// helper function in case where array is contained in array, e.g. due to new employment returned by SF oData API
def ArrayList homogenifyArrayList(ArrayList list, Message message)
{
    def debugInfoText = "";
    def newlist = [];

    debugInfoText += "-----> list" + System.lineSeparator() + System.lineSeparator();
    debugInfoText += JsonOutput.prettyPrint(JsonOutput.toJson(list));
    debugInfoText += System.lineSeparator() + System.lineSeparator();

    if (list == null)    {
        // do not process
        debugInfoText += "-----> list is null" + System.lineSeparator();
        return list;
    }
    else if (list.getClass().toString().equals("class java.util.ArrayList"))
    {
        debugInfoText += "-----> list is ArrayList" + System.lineSeparator();
        // check for array inside array (SFAdapter will return this structure for employess which have been transfered to another company)
        list.each { elem ->

            debugInfoText += "-----> elem class " + elem.getClass().toString() + System.lineSeparator();

            if (elem.getClass().toString().equals("class java.util.ArrayList")) {
                elem.each { innerElem ->

                    debugInfoText += "-----> innerElem class " + innerElem.getClass().toString() + System.lineSeparator();

                    newlist << innerElem; //append innerElem to list
                }
            }
            else {
                newlist << elem; //append elem to list
            }
        }
    }
    else
    {
        debugInfoText += "-----> other class than list detected " + list.getClass().toString() + System.lineSeparator();
    }
    debugInfoText += "-----> newlist returned: " + System.lineSeparator() + JsonOutput.prettyPrint(JsonOutput.toJson(newlist)) + System.lineSeparator();

    // **************************** DEBUG LOGGING ****************************
    def props = message.getProperties();
    def debugLoggingEnabled = props.get("debugLoggingEnabled") ?: "";
    if (debugLoggingEnabled.equalsIgnoreCase("true") || debugLoggingEnabled.equalsIgnoreCase("yes"))
    {
        def processDebugLog = props.get("processDebugLog");
        processDebugLog += "homogenifyArrayList" + System.lineSeparator() + debugInfoText + System.lineSeparator();
        //message.setProperty("processDebugLog", processDebugLog);
    }
    // **************************** DEBUG LOGGING ****************************

    def messageLog = messageLogFactory.getMessageLog(message);
    if(messageLog != null)
    {
        //    messageLog.addAttachmentAsString("DEBUG Log homogenifyArrayList", "debug message for homogenifyArrayList:" + System.lineSeparator() + debugInfoText + System.lineSeparator(), "text/plain");
    }

    return newlist;
}
