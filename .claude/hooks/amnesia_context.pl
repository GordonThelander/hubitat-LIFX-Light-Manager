#!/usr/bin/perl
use strict;
use warnings;
use Cwd qw(getcwd);

# Registered at user level (~/.claude/settings.json) so every worktree under
# ClaudeGit inherits it automatically - project-level .claude/settings.json is
# NOT shared between a repo and its git worktrees. Absolute path + cwd guard
# keep it scoped to this tree instead of firing (uselessly) on unrelated
# projects elsewhere on the machine.
my $root = 'C:/Users/gordo/Downloads/ClaudeGit';
my $amnesia_path = "$root/.claude/amnesia.md";

my $cwd = getcwd();
$cwd =~ s{\\}{/}g;

unless (index($cwd, $root) == 0) {
    print '{}';
    exit 0;
}

open(my $fh, '<', $amnesia_path) or die "cannot open amnesia.md: $!";
local $/;
my $content = <$fh>;
close($fh);

$content =~ s/\\/\\\\/g;
$content =~ s/"/\\"/g;
$content =~ s/\r//g;
$content =~ s/\n/\\n/g;
$content =~ s/\t/\\t/g;

print '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"' . $content . '"}}';
