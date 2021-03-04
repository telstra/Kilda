//package org.openkilda.functionaltests.helpers
//
//import com.athaydes.spockframework.report.internal.SpecData
//import com.athaydes.spockframework.report.template.TemplateReportCreator
//import com.athaydes.spockframework.report.util.Utils
//
///**
// * This enables a special form of spock report that will use current specifications to generate test cases
// * in markdown format. In order to enable it pass
// *  -Dcom.athaydes.spockframework.report.IReportCreator=org.openkilda.functionaltests.helpers.TestCaseReportCreator
// *  when starting the build.
// */
//class TestCaseReportCreator extends TemplateReportCreator {
//    boolean clearedSummary = false
//    File summaryFile
//    List<SpecData> specs = []
//
//    TestCaseReportCreator() {
//        super()
//        specTemplateFile = "/test_case_template.md"
//        reportFileExtension = "md"
//        summaryTemplateFile = "/templateReportCreator/summary-template.md"
//        summaryFileName = "summary.md"
//    }
//
//    @Override
//    void createReportFor(SpecData data) {
//        super.createReportFor(data)
//        specs << data
//        summaryFile = new File("$outputDir/$summaryFileName")
//        if (!clearedSummary) {
//            summaryFile.delete()
//            clearedSummary = true
//        }
//        summaryFile.append(reportFor(data))
//    }
//
//    @Override
//    void done() {
//        //create table of contents at the top of the report
//        def toc = specs.collect {
//            def name = Utils.getSpecClassName(it).split("\\.").last() - ~/Spec$/
//            "- [$name](#${name.toLowerCase()})\n"
//        }.join("")
//        summaryFile.text = toc + summaryFile.text
//
//    }
//}
