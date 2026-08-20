with open("app/src/main/java/com/example/data/repository/Repositories.kt", "r") as f:
    content = f.read()

# 1. Remove the early `pendingDao.updateStatus`
content = content.replace('pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)', '')

# 2. Add it at the end of the success blocks
# For the case where it's identical:
old_identical = "return@withTransaction existing"
new_identical = 'pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)\n                                return@withTransaction existing'
content = content.replace(old_identical, new_identical)

# For the new entry creation:
old_creation = 'val chargeEntry = addDebtInternal(savedAcc.id, operationPrice, finalNote, businessTransactionId)\n                        chargeEntry\n                    } else {\n                        null\n                    }\n                } else {\n                    null\n                }'
new_creation = 'val chargeEntry = addDebtInternal(savedAcc.id, operationPrice, finalNote, businessTransactionId)\n                        pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)\n                        chargeEntry\n                    } else {\n                        throw IllegalStateException("MISSING_LOCAL_FINANCIAL_TARGET: Cannot materialize financial position for missing local account ${op.accountId}")\n                    }\n                } else {\n                    pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)\n                    null\n                }'
content = content.replace(old_creation, new_creation)

with open("app/src/main/java/com/example/data/repository/Repositories.kt", "w") as f:
    f.write(content)
