// This is Groovy Flowstep Version 2.x, running with Groovy runtime 4, Downgrade the script if older behaviour needed.

import com.sap.it.script.v2.api.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.commons.lang3.StringEscapeUtils

/**
 * Transformiert eine SuccessFactors-PerPerson-Antwort in die XML-Zielstruktur.
 *
 * Wesentliche Auswahlregeln:
 * - Pro startDate wird nur der EmpJob-Eintrag mit der höchsten seqNumber berücksichtigt.
 * - Priorität: aktuell gültig > zukünftiger Eintritt innerhalb 33 Tagen > Austritt innerhalb 30 Tagen.
 * - Für PerPersonal wird der am Referenzdatum gültige Zeitschnitt verwendet.
 * - Als Referenzdatum dient bei aktuellem Job heute, bei zukünftigem Job dessen Startdatum,
 *   bei kürzlich beendetem Job dessen Enddatum.
 */
Message processData(Message message) {
    Map<String, Object> props = message.getProperties()
    String body = message.getBody(String) ?: ''
    String xmlEnvelope
    StringBuilder debug = new StringBuilder()

    try {
        if (body.trim().isEmpty()) {
            debug << '-----> body is empty' << System.lineSeparator()
            xmlEnvelope = createDefaultXMLResponse()
        } else {
            Map sourceJson = (Map) new JsonSlurper().parseText(body)

            String picklistMappings =
                (props.get('picklistMappings') ?: '{"picklistMappings":[]}').toString()

            String xmlContent = processPerPersonEntity(
                sourceJson,
                picklistMappings,
                message,
                false
            )

            xmlEnvelope = xmlContent
                ? "<Records>${xmlContent}</Records>"
                : createDefaultXMLResponse()
        }

        message.setBody(xmlEnvelope)
        message.setProperty('IgnoreMessageForLog', 'no')
    } catch (Exception e) {
        debug << '-----> Exception: '
              << e.getClass().getName()
              << ': '
              << (e.getMessage() ?: '')
              << System.lineSeparator()

        xmlEnvelope = createDefaultXMLResponse()
        message.setBody(xmlEnvelope)
        message.setProperty('IgnoreMessageForLog', 'no')
    }

    appendDebugLog(
        message,
        'processData',
        debug.toString() +
            'incoming message body:' + System.lineSeparator() +
            body + System.lineSeparator() + System.lineSeparator() +
            'outgoing message body:' + System.lineSeparator() +
            xmlEnvelope + System.lineSeparator()
    )

    return message
}

String processPerPersonEntity(
    Map jsonMap,
    String picklistMappings,
    Message message,
    boolean forceUpdate
) {
    StringBuilder debug = new StringBuilder()

    try {
        Map perPerson = jsonMap?.PerPerson as Map
        if (!perPerson) {
            debug << 'PerPerson is missing' << System.lineSeparator()
            appendDebugLog(message, 'processPerPersonEntity', debug.toString())
            return ''
        }

        String personIdExternal = valueAsString(perPerson.personIdExternal)
        message.setProperty('currentEmployeeNo', personIdExternal)

        debug << 'processPerPersonEntity processing:' << System.lineSeparator()
        debug << 'personIdExternal is ' << personIdExternal << System.lineSeparator()

        List<Map> employments = flattenOneLevel(
            asList(perPerson?.employmentNav?.EmpEmployment)
        ).findAll { it instanceof Map } as List<Map>

        List<Map> jobCandidates = []

        employments.each { Map employment ->
            boolean hiringNotCompleted = toBoolean(employment.hiringNotCompleted)

            if (hiringNotCompleted) {
                debug << 'Skipping employment because hiringNotCompleted=true'
                      << System.lineSeparator()
                return
            }

            List<Map> jobs = flattenOneLevel(
                asList(employment?.jobInfoNav?.EmpJob)
            ).findAll { it instanceof Map } as List<Map>

            Map<String, BigDecimal> highestSeqByStartDate = [:]

            jobs.each { Map job ->
                String startDateKey = formatDateYYYMMDD(valueAsString(job.startDate))
                BigDecimal seq = toBigDecimal(job.seqNumber)

                if (!highestSeqByStartDate.containsKey(startDateKey) ||
                    highestSeqByStartDate[startDateKey] < seq) {
                    highestSeqByStartDate[startDateKey] = seq
                }
            }

            jobs.each { Map job ->
                String startDateKey = formatDateYYYMMDD(valueAsString(job.startDate))
                BigDecimal seq = toBigDecimal(job.seqNumber)

                if (highestSeqByStartDate[startDateKey] == seq) {
                    jobCandidates << [
                        employment: employment,
                        job       : job
                    ]
                } else {
                    debug << 'Skipping lower sequence job for startDate '
                          << startDateKey
                          << ', seqNumber '
                          << valueAsString(job.seqNumber)
                          << System.lineSeparator()
                }
            }
        }

        Map selectedCandidate = selectBestJobCandidate(jobCandidates, debug)

        if (!selectedCandidate) {
            debug << 'No valid EmpJob candidate found'
                  << System.lineSeparator()
            appendDebugLog(message, 'processPerPersonEntity', debug.toString())
            return ''
        }

        Map employment = selectedCandidate.employment as Map
        Map job = selectedCandidate.job as Map
        String candidateType = selectedCandidate.type as String
        Date referenceDate = selectedCandidate.referenceDate as Date

        debug << 'Selected candidate type: '
              << candidateType
              << System.lineSeparator()
        debug << 'Selected EmpJob startDate: '
              << valueAsString(job.startDate)
              << System.lineSeparator()
        debug << 'Selected EmpJob endDate: '
              << valueAsString(job.endDate)
              << System.lineSeparator()
        debug << 'Selected EmpJob seqNumber: '
              << valueAsString(job.seqNumber)
              << System.lineSeparator()
        debug << 'Reference date for personal data: '
              << referenceDate.format('yyyy-MM-dd')
              << System.lineSeparator()

        Map personal = selectPersonalSlice(
            perPerson?.personalInfoNav?.PerPersonal,
            referenceDate,
            debug
        )

        Map it0036 = selectIt0036Slice(
            employment?.userNav?.User?.externalCodeOfcust_HRC_IT0036Nav?.cust_HRC_IT0036,
            referenceDate,
            debug
        )

        String persNr = leftPadEmployeeNumber(personIdExternal)
        String birthDate = formatDateYYYMMDD(valueAsString(perPerson.dateOfBirth))
        String costCenter = valueAsString(job.costCenter)
        String manager = formatManager(valueAsString(job.managerId))
        String startDate = formatDateYYYMMDD(valueAsString(employment.startDate))

        String resignationDate = formatDateYYYMMDD(valueAsString(employment.endDate))
        String lastDateWorkedDate = formatDateYYYMMDD(valueAsString(employment.lastDateWorked))
        String endDate = lastDateWorkedDate ?: resignationDate

        String company = valueAsString(job.company)
        String locationCode = valueAsString(job.location)
        String departmentCode = valueAsString(job.department)

        String departmentName =
            valueAsString(job?.departmentNav?.FODepartment?.name)

        String departmentNameShort =
            valueAsString(job?.departmentNav?.FODepartment?.cust_apothekenkrzl)

        String networkAccess =
            valueAsString(job?.customString13Nav?.PicklistOption?.externalCode)

        String persType =
            valueAsString(job?.employeeClassNav?.PicklistOption?.externalCode)

        String employeeType =
            valueAsString(job?.customString9Nav?.PicklistOption?.externalCode)

        String jobTitle =
            valueAsString(job?.positionNav?.Position?.externalName_defaultValue)

        String ahvNummer = valueAsString(it0036?.cust_NAHVN)

        String lastName = valueAsString(personal?.lastName).trim()
        String firstName = valueAsString(personal?.firstName).trim()
        String gender = valueAsString(personal?.gender)

        String language =
            valueAsString(personal?.nativePreferredLangNav?.PicklistOption?.externalCode)

        String firstNameIT = valueAsString(personal?.preferredName).trim()
        String lastNameIT = valueAsString(personal?.customString1).trim()

        debug << 'PersNr ' << persNr << System.lineSeparator()
        debug << 'BirthDate ' << birthDate << System.lineSeparator()
        debug << 'CostCenter ' << costCenter << System.lineSeparator()
        debug << 'Manager ' << manager << System.lineSeparator()
        debug << 'StartDate ' << startDate << System.lineSeparator()
        debug << 'EndDate ' << endDate << System.lineSeparator()
        debug << 'Company ' << company << System.lineSeparator()
        debug << 'LocationCode ' << locationCode << System.lineSeparator()
        debug << 'DepartmentCode ' << departmentCode << System.lineSeparator()
        debug << 'DepartmentName ' << departmentName << System.lineSeparator()
        debug << 'DepartmentNameShort ' << departmentNameShort << System.lineSeparator()
        debug << 'Lastname ' << lastName << System.lineSeparator()
        debug << 'Firstname ' << firstName << System.lineSeparator()
        debug << 'Gender ' << gender << System.lineSeparator()
        debug << 'NetworkAccess ' << networkAccess << System.lineSeparator()
        debug << 'Language ' << language << System.lineSeparator()
        debug << 'PersType ' << persType << System.lineSeparator()
        debug << 'EmployeeType ' << employeeType << System.lineSeparator()
        debug << 'JobTitle ' << jobTitle << System.lineSeparator()
        debug << 'AHVNummer ' << ahvNummer << System.lineSeparator()
        debug << 'FirstnameIT ' << firstNameIT << System.lineSeparator()
        debug << 'LastnameIT ' << lastNameIT << System.lineSeparator()

        String xml = buildRecordXml([
            PersNr             : persNr,
            BirthDate          : birthDate,
            CostCenter         : costCenter,
            Manager            : manager,
            StartDate          : startDate,
            EndDate            : endDate,
            Company            : company,
            LocationCode       : locationCode,
            DepartmentCode     : departmentCode,
            DepartmentName     : departmentName,
            DepartmentNameShort: departmentNameShort,
            Lastname           : lastName,
            Firstname          : firstName,
            Gender             : gender,
            NetworkAccess      : networkAccess,
            Language           : language,
            PersType           : persType,
            EmployeeType       : employeeType,
            JobTitle           : jobTitle,
            AHVNummer          : ahvNummer,
            FirstnameIT        : firstNameIT,
            LastnameIT         : lastNameIT
        ])

        debug << 'xmlResponse:' << System.lineSeparator()
              << xml << System.lineSeparator()

        appendDebugLog(message, 'processPerPersonEntity', debug.toString())
        return xml
    } catch (Exception e) {
        debug << 'Exception: '
              << e.getClass().getName()
              << ': '
              << (e.getMessage() ?: '')
              << System.lineSeparator()

        appendDebugLog(message, 'processPerPersonEntity', debug.toString())
        return ''
    }
}

/**
 * Wählt den fachlich besten EmpJob-Kandidaten.
 *
 * Priorität:
 * 1. aktuell gültiger Job
 * 2. zukünftiger Job innerhalb von 33 Tagen
 * 3. in den letzten 30 Tagen beendeter Job
 */
Map selectBestJobCandidate(List<Map> candidates, StringBuilder debug) {
    Date now = new Date()

    Calendar futureCalendar = Calendar.getInstance()
    futureCalendar.setTime(now)
    futureCalendar.add(Calendar.DAY_OF_MONTH, 33)
    Date maxFutureDate = futureCalendar.time

    Calendar pastCalendar = Calendar.getInstance()
    pastCalendar.setTime(now)
    pastCalendar.add(Calendar.DAY_OF_MONTH, -30)
    Date minPastDate = pastCalendar.time

    List<Map> current = []
    List<Map> future = []
    List<Map> past = []

    candidates.each { Map candidate ->
        Map job = candidate.job as Map

        if (valueAsString(job.event) == '449') {
            debug << 'Skipping termination event 449 for job starting '
                  << valueAsString(job.startDate)
                  << System.lineSeparator()
            return
        }

        Date start = parseSfDate(valueAsString(job.startDate))
        Date end = parseSfDateOrMax(valueAsString(job.endDate))

        if (!start || !end) {
            debug << 'Skipping job because startDate or endDate is invalid'
                  << System.lineSeparator()
            return
        }

        Map enriched = [
            employment : candidate.employment,
            job        : job,
            startDate  : start,
            endDate    : end
        ]

        if (start <= now && now <= end) {
            enriched.type = 'CURRENT'
            enriched.referenceDate = now
            current << enriched
        } else if (start > now && start < maxFutureDate) {
            enriched.type = 'FUTURE'
            enriched.referenceDate = start
            future << enriched
        } else if (end < now && end >= minPastDate) {
            enriched.type = 'PAST'
            enriched.referenceDate = end
            past << enriched
        }
    }

    if (current) {
        return current.sort { a, b ->
            b.startDate <=> a.startDate
        }.first()
    }

    if (future) {
        return future.sort { a, b ->
            a.startDate <=> b.startDate
        }.first()
    }

    if (past) {
        return past.sort { a, b ->
            b.endDate <=> a.endDate
        }.first()
    }

    return null
}

/**
 * Wählt den PerPersonal-Zeitschnitt, der am Referenzdatum gültig ist.
 *
 * Damit wird nicht mehr der erste Eintrag als dauerhaftes Fallback übernommen.
 * Bei mehreren Treffern gewinnt der Eintrag mit dem neuesten startDate.
 */
Map selectPersonalSlice(
    Object personalData,
    Date referenceDate,
    StringBuilder debug
) {
    List<Map> personalSlices = flattenOneLevel(
        asList(personalData)
    ).findAll { it instanceof Map } as List<Map>

    List<Map> validSlices = personalSlices.findAll { Map slice ->
        Date start = parseSfDate(valueAsString(slice.startDate))
        Date end = parseSfDateOrMax(valueAsString(slice.endDate))

        start && end && start <= referenceDate && referenceDate <= end
    }

    if (validSlices) {
        Map selected = validSlices.sort { a, b ->
            parseSfDate(valueAsString(b.startDate)) <=>
                parseSfDate(valueAsString(a.startDate))
        }.first()

        debug << 'Selected PerPersonal slice: '
              << valueAsString(selected.startDate)
              << ' - '
              << valueAsString(selected.endDate)
              << ', lastname='
              << valueAsString(selected.lastName)
              << System.lineSeparator()

        return selected
    }

    debug << 'No PerPersonal slice valid on reference date '
          << referenceDate.format('yyyy-MM-dd')
          << System.lineSeparator()

    /*
     * Robuster Fallback:
     * Falls kein gültiger Zeitschnitt vorhanden ist, wird der zeitlich neueste
     * Eintrag vor oder am Referenzdatum verwendet. Dadurch wird nicht einfach
     * der erste Array-Eintrag genommen.
     */
    List<Map> previousSlices = personalSlices.findAll { Map slice ->
        Date start = parseSfDate(valueAsString(slice.startDate))
        start && start <= referenceDate
    }

    if (previousSlices) {
        Map fallback = previousSlices.sort { a, b ->
            parseSfDate(valueAsString(b.startDate)) <=>
                parseSfDate(valueAsString(a.startDate))
        }.first()

        debug << 'Using latest previous PerPersonal slice as fallback: '
              << valueAsString(fallback.startDate)
              << ', lastname='
              << valueAsString(fallback.lastName)
              << System.lineSeparator()

        return fallback
    }

    return [:]
}

/**
 * Wählt den am Referenzdatum gültigen IT0036-/AHV-Zeitschnitt.
 */
Map selectIt0036Slice(
    Object it0036Data,
    Date referenceDate,
    StringBuilder debug
) {
    List<Map> slices = flattenOneLevel(
        asList(it0036Data)
    ).findAll { it instanceof Map } as List<Map>

    List<Map> valid = slices.findAll { Map slice ->
        Date start = parseSfDate(valueAsString(slice.effectiveStartDate))
        Date end = parseSfDateOrMax(valueAsString(slice.effectiveEndDate))

        start && end && start <= referenceDate && referenceDate <= end
    }

    if (valid) {
        Map selected = valid.sort { a, b ->
            parseSfDate(valueAsString(b.effectiveStartDate)) <=>
                parseSfDate(valueAsString(a.effectiveStartDate))
        }.first()

        debug << 'Selected IT0036 slice: '
              << valueAsString(selected.effectiveStartDate)
              << ' - '
              << valueAsString(selected.effectiveEndDate)
              << System.lineSeparator()

        return selected
    }

    return slices ? slices.first() : [:]
}

String buildRecordXml(Map<String, String> values) {
    List<String> fields = [
        'PersNr',
        'BirthDate',
        'CostCenter',
        'Manager',
        'StartDate',
        'EndDate',
        'Company',
        'LocationCode',
        'DepartmentCode',
        'DepartmentName',
        'DepartmentNameShort',
        'Lastname',
        'Firstname',
        'Gender',
        'NetworkAccess',
        'Language',
        'PersType',
        'EmployeeType',
        'JobTitle',
        'AHVNummer',
        'FirstnameIT',
        'LastnameIT'
    ]

    StringBuilder xml = new StringBuilder('<Record>')

    fields.each { String field ->
        xml << '<' << field << '>'
        xml << escapeXml(values[field])
        xml << '</' << field << '>'
    }

    xml << '</Record>'
    return xml.toString()
}

String createDefaultXMLResponse() {
    return '<Records>' + buildRecordXml([
        PersNr             : '',
        BirthDate          : '',
        CostCenter         : '',
        Manager            : '',
        StartDate          : '',
        EndDate            : '',
        Company            : '',
        LocationCode       : '',
        DepartmentCode     : '',
        DepartmentName     : '',
        DepartmentNameShort: '',
        Lastname           : '',
        Firstname          : '',
        Gender             : '',
        NetworkAccess      : '',
        Language           : '',
        PersType           : '',
        EmployeeType       : '',
        JobTitle           : '',
        AHVNummer          : '',
        FirstnameIT        : '',
        LastnameIT         : ''
    ]) + '</Records>'
}

String formatDateYYYMMDD(String tempDate) {
    Date date = parseSfDate(tempDate)

    if (!date) {
        return ''
    }

    String formatted = date.format('yyyyMMdd')
    return formatted == '99991231' ? '' : formatted
}

Date parseSfDate(String value) {
    if (!value?.trim()) {
        return null
    }

    return Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS", value.trim())
}

Date parseSfDateOrMax(String value) {
    if (!value?.trim()) {
        return Date.parse(
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            '9999-12-31T00:00:00.000'
        )
    }

    return parseSfDate(value)
}

String leftPadEmployeeNumber(String employeeNumber) {
    String value = employeeNumber ?: ''
    return ('000000000000' + value).takeRight(8)
}

String formatManager(String managerId) {
    if (!managerId || managerId == 'NO_MANAGER') {
        return ''
    }

    return ('000000000000' + managerId).takeRight(8)
}

String escapeXml(Object value) {
    return StringEscapeUtils.escapeXml11(valueAsString(value)) ?: ''
}

String valueAsString(Object value) {
    return value == null ? '' : value.toString()
}

boolean toBoolean(Object value) {
    return value != null && value.toString().equalsIgnoreCase('true')
}

BigDecimal toBigDecimal(Object value) {
    try {
        return new BigDecimal(valueAsString(value) ?: '0')
    } catch (Exception ignored) {
        return BigDecimal.ZERO
    }
}

List asList(Object value) {
    if (value == null) {
        return []
    }

    if (value instanceof List) {
        return value as List
    }

    return [value]
}

List flattenOneLevel(List list) {
    List result = []

    list.each { Object element ->
        if (element instanceof List) {
            result.addAll(element as List)
        } else {
            result << element
        }
    }

    return result
}

String processPicklistMapping(
    String targetFieldname,
    String value,
    String picklistMappings,
    Message message
) {
    String returnValue = value ?: ''

    if (!picklistMappings?.trim()) {
        return returnValue
    }

    Map mappingRoot = (Map) new JsonSlurper().parseText(picklistMappings)
    List mappings = asList(mappingRoot.picklistMappings)

    mappings.each { Object rawMapping ->
        if (!(rawMapping instanceof Map)) {
            return
        }

        Map mapping = rawMapping as Map

        if (valueAsString(mapping.fieldname) == targetFieldname) {
            asList(mapping.values).each { Object rawItem ->
                if (!(rawItem instanceof Map)) {
                    return
                }

                Map item = rawItem as Map

                if (valueAsString(item.source) == value) {
                    returnValue = valueAsString(item.target)
                }
            }
        }
    }

    return returnValue
}

ArrayList convertToArrayList(Object jsonElem, Message message) {
    return new ArrayList(asList(jsonElem))
}

ArrayList homogenifyArrayList(ArrayList list, Message message) {
    return new ArrayList(flattenOneLevel(list ?: []))
}

String getCompanyPerDate(
    Map jsonMap,
    String targetDate,
    String legalEntityMappings,
    Message message
) {
    Date referenceDate = targetDate?.trim()
        ? parseSfDate(targetDate)
        : new Date()

    List<Map> jobs = flattenOneLevel(
        asList(jsonMap?.jobInfoNav?.EmpJob)
    ).findAll { it instanceof Map } as List<Map>

    Map selected = jobs.find { Map job ->
        Date start = parseSfDate(valueAsString(job.startDate))
        Date end = parseSfDateOrMax(valueAsString(job.endDate))

        start && end && start <= referenceDate && referenceDate <= end
    }

    return selected ? valueAsString(selected.company) : 'undefined'
}

void appendDebugLog(
    Message message,
    String section,
    String text
) {
    Map<String, Object> props = message.getProperties()

    String enabled =
        (props.get('debugLoggingEnabled') ?: '').toString()

    if (!enabled.equalsIgnoreCase('true') &&
        !enabled.equalsIgnoreCase('yes')) {
        return
    }

    String current =
        (props.get('processDebugLog') ?: '').toString()

    current += section + System.lineSeparator()
    current += text + System.lineSeparator()

    message.setProperty('processDebugLog', current)
}
