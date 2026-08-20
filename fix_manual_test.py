import re
with open("app/src/test/java/com/example/ManualVerificationResolutionTest.kt", "r") as f:
    content = f.read()

content = content.replace("UserListResponse(emptyList(), 0)", "com.example.core.model.UserListResponse(emptyList<com.example.core.model.UserListItem>(), 0)")
content = content.replace("= UserDetailResult()", "= com.example.core.model.UserDetail()")

with open("app/src/test/java/com/example/ManualVerificationResolutionTest.kt", "w") as f:
    f.write(content)
