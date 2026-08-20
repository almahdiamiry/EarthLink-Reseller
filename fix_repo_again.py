import re

with open("app/src/main/java/com/example/data/repository/Repositories.kt", "r") as f:
    content = f.read()

# Strip all injected updates that precede a return
bad_str = 'pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)\n                                return@withTransaction existing'
content = content.replace(bad_str, 'return@withTransaction existing')

bad_str2 = 'pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)\n                                return@withTransaction existingByIntent'
content = content.replace(bad_str2, 'return@withTransaction existingByIntent')

bad_str3 = 'pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)\n                                return@withTransaction existingByTx'
content = content.replace(bad_str3, 'return@withTransaction existingByTx')

# Now add the correct update back inside `resolvePendingOperationVerifiedSuccess`
# Specifically, replace `if (isIdentical) {\n                                return@withTransaction existing\n                            } else {`
target = 'if (isIdentical) {\n                                return@withTransaction existing\n                            } else {'
replacement = 'if (isIdentical) {\n                                pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)\n                                return@withTransaction existing\n                            } else {'

content = content.replace(target, replacement)

with open("app/src/main/java/com/example/data/repository/Repositories.kt", "w") as f:
    f.write(content)
