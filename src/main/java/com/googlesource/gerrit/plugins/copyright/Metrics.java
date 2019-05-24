// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.copyright;

import com.google.gerrit.metrics.Counter0;
import com.google.gerrit.metrics.Counter1;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Description.Units;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer0;
import com.google.gerrit.metrics.Timer1;
import com.google.inject.Singleton;
import javax.inject.Inject;

@Singleton
class Metrics {
  final Timer0 readConfigTimer;
  final Timer0 checkConfigTimer;
  final Timer0 testConfigTimer;
  final Counter1<String> postReviewErrors;
  final Counter1<String> addReviewerErrors;
  final Counter1<String> projectStateErrors;
  final Counter1<String> projectConfigErrors;
  final Counter1<String> configurationErrors;
  final Counter0 reviewCount;
  final Counter0 commentCount;
  final Timer0 reviewTimer;
  final Counter1<String> reviewCountByProject;
  final Counter1<String> commentCountByProject;
  final Timer1<String> reviewTimerByProject;
  final Timer0 scanRevisionTimer;
  final Timer0 scanFileTimer;
  final Counter1<String> scanCountByProject;
  final Timer1<String> scanRevisionTimerByProject;
  final Timer1<String> scanFileTimerByProject;
  final Counter1<String> scanCountByBranch;
  final Timer1<String> scanRevisionTimerByBranch;
  final Timer1<String> scanFileTimerByBranch;
  final Counter1<String> skippedReviewWarnings;
  final Counter1<String> scanErrors;
  final Counter0 errors;

  @Inject
  Metrics(MetricMaker metricMaker) {
    Field<String> project = Field.ofString("project", "project name");
    Field<String> branch = Field.ofString("branch", "branch name");

    readConfigTimer =
        metricMaker.newTimer(
            "read_config_latency",
            new Description("Time spent reading and parsing plugin configurations")
                .setCumulative()
                .setUnit(Units.MICROSECONDS));
    checkConfigTimer =
        metricMaker.newTimer(
            "check_config_latency",
            new Description("Time spent testing proposed plugin configurations")
                .setCumulative()
                .setUnit(Units.MICROSECONDS));
    testConfigTimer =
        metricMaker.newTimer(
            "test_config_latency",
            new Description("Time spent testing configurations against difficult file pattern")
                .setCumulative()
                .setUnit(Units.MICROSECONDS));
    postReviewErrors =
        metricMaker.newCounter(
            "post_review_error_count",
            new Description("Number of failed attempts to post reviews")
                .setRate()
                .setUnit("errors"),
            project);
    addReviewerErrors =
        metricMaker.newCounter(
            "add_reviewer_error_count",
            new Description("Number of failed attempts to add a reviewer")
                .setRate()
                .setUnit("errors"),
            project);
    projectStateErrors =
        metricMaker.newCounter(
            "read_project_state_error_count",
            new Description("Number of failed attempts to read project state")
                .setRate()
                .setUnit("errors"),
            project);
    projectConfigErrors =
        metricMaker.newCounter(
            "get_project_config_error_count",
            new Description("Number of failed attempts to get config from project state")
                .setRate()
                .setUnit("errors"),
            project);
    configurationErrors =
        metricMaker.newCounter(
            "read_configuration_error_count",
            new Description("Number of failed attempts to read configuration")
                .setRate()
                .setUnit("errors"),
            project);
    reviewCount =
        metricMaker.newCounter(
            "review_count",
            new Description("Total number of posted reviews").setRate().setUnit("reviews"));
    commentCount =
        metricMaker.newCounter(
            "comment_count",
            new Description("Total number of posted review comments")
                .setRate()
                .setUnit("comments"));
    reviewTimer =
        metricMaker.newTimer(
            "review_latency",
            new Description("Time spent posting reviews to revisions")
                .setCumulative()
                .setUnit(Units.MICROSECONDS));
    reviewCountByProject =
        metricMaker.newCounter(
            "review_count_by_project",
            new Description("Total number of posted reviews").setRate().setUnit("reviews"),
            project);
    commentCountByProject =
        metricMaker.newCounter(
            "comment_count_by_project",
            new Description("Total number of posted review comments")
                .setRate()
                .setUnit("comments"),
            project);
    reviewTimerByProject =
        metricMaker.newTimer(
            "review_latency_by_project",
            new Description("Time spent posting reviews to revisions")
                .setCumulative()
                .setUnit(Units.MICROSECONDS),
            project);
    scanRevisionTimer =
        metricMaker.newTimer(
            "scan_revision_latency",
            new Description("Time spent scanning entire revisions")
                .setCumulative()
                .setUnit(Units.MILLISECONDS));
    scanFileTimer =
        metricMaker.newTimer(
            "scan_file_latency",
            new Description("Time spent scanning each file")
                .setCumulative()
                .setUnit(Units.MICROSECONDS));
    scanCountByProject =
        metricMaker.newCounter(
            "scan_count_by_project",
            new Description("Total number of copyright scans").setRate().setUnit("scans"),
            project);
    scanRevisionTimerByProject =
        metricMaker.newTimer(
            "scan_revision_latency_by_project",
            new Description("Time spent scanning entire revisions")
                .setCumulative()
                .setUnit(Units.MILLISECONDS),
            project);
    scanFileTimerByProject =
        metricMaker.newTimer(
            "scan_file_latency_by_project",
            new Description("Time spent scanning each file")
                .setCumulative()
                .setUnit(Units.MICROSECONDS),
            project);
    scanCountByBranch =
        metricMaker.newCounter(
            "scan_count_by_branch",
            new Description("Total number of copyright scans").setRate().setUnit("scans"),
            branch);
    scanRevisionTimerByBranch =
        metricMaker.newTimer(
            "scan_revision_latency_by_branch",
            new Description("Time spent scanning entire revisions")
                .setCumulative()
                .setUnit(Units.MILLISECONDS),
            branch);
    scanFileTimerByBranch =
        metricMaker.newTimer(
            "scan_file_latency_by_branch",
            new Description("Time spent scanning each file")
                .setCumulative()
                .setUnit(Units.MICROSECONDS),
            branch);
    skippedReviewWarnings =
        metricMaker.newCounter(
            "skipped_scan_warning_count",
            new Description("Number revision scans skipped due to configuration problems")
                .setRate()
                .setUnit("warnings"),
            project);
    scanErrors =
        metricMaker.newCounter(
            "failed_scan_error_count",
            new Description("Number of failed attempts to scan revisions")
                .setRate()
                .setUnit("errors"),
            project);
    errors =
        metricMaker.newCounter(
            "error_count",
            new Description("Number of errors of any kind").setRate().setUnit("errors"));
  }
}
