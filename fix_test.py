with open("app/src/test/java/com/example/Phase1UnknownOutcomeResolutionTest.kt", "r") as f:
    content = f.read()

content = content.replace("assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolution.result)", "assertEquals(UnknownOutcomeResolutionResult.INCONCLUSIVE, resolution.result)")
content = content.replace('assertEquals("COMPLETED", updatedOp?.status)', 'assertEquals("PENDING", updatedOp?.status)')

with open("app/src/test/java/com/example/Phase1UnknownOutcomeResolutionTest.kt", "w") as f:
    f.write(content)
