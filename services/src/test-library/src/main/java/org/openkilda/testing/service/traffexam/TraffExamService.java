/* Copyright 2018 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.testing.service.traffexam;

import org.openkilda.testing.service.traffexam.model.Address;
import org.openkilda.testing.service.traffexam.model.Exam;
import org.openkilda.testing.service.traffexam.model.ExamReport;
import org.openkilda.testing.service.traffexam.model.ExamResources;
import org.openkilda.testing.service.traffexam.model.Host;
import org.openkilda.testing.service.traffexam.model.LldpData;
import org.openkilda.testing.service.traffexam.model.UdpData;
import org.openkilda.testing.service.traffexam.networkpool.Inet4ValueException;

import java.util.List;

public interface TraffExamService {

    List<Host> listHosts();

    Host hostByName(String name) throws NoResultsFoundException;

    ExamResources startExam(Exam exam)
            throws NoResultsFoundException, OperationalException;

    ExamResources startExam(Exam exam, boolean udp)
            throws NoResultsFoundException, OperationalException;

    ExamReport waitExam(Exam exam);

    ExamReport waitExam(Exam exam, boolean cleanup);

    boolean isFinished(Exam exam);

    ExamReport fetchReport(Exam exam) throws NoResultsFoundException, ExamNotFinishedException;

    void stopExam(Exam exam) throws NoResultsFoundException;

    void stopAll();

    Address allocateFreeAddress(Host host, int vlan) throws OperationalException, Inet4ValueException;

    void releaseAddress(Address subject);

    void sendLldp(Address address, LldpData lldpData);

    void sendUdp(Address address, UdpData udpData);
}
