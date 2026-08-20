import glob
import re

for filepath in glob.glob("app/src/test/java/com/example/*.kt"):
    with open(filepath, "r") as f:
        content = f.read()

    # Fix LoginResponse()
    content = content.replace("LoginResponse()", 'LoginResponse(accessToken="test", tokenType="Bearer", expiresIn=3600)')

    # Fix UserListResponse(0, emptyList()) -> UserListResponse(emptyList(), 0)
    content = content.replace("UserListResponse(0, emptyList())", "UserListResponse(emptyList(), 0)")
    content = content.replace("UserListResponse(1, ", "UserListResponse(")

    # Fix Unresolved reference 'UserListItem' and 'UserDetail' by ensuring imports or using fully qualified names
    content = content.replace("val searchUsersResult = com.example.core.model.UserListResponse", "var searchUsersResult = com.example.core.model.UserListResponse")
    content = content.replace("val userDetailResult = com.example.core.model.UserDetail", "var userDetailResult = com.example.core.model.UserDetail")

    with open(filepath, "w") as f:
        f.write(content)
