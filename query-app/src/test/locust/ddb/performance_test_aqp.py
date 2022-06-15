# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: MIT-0

from locust import HttpUser, between, task
from random import randrange, choice, uniform
from datetime import timedelta, datetime
from urllib3 import PoolManager

user_name = "large-query-app"
table_name='"performance_test_table"'
pw = "secretEXAMPLE"
number_accounts = 100
number_customers = 100
number_users = 1000
status_codes = ["CREATED", "PAYED", "CANCELED", "PROCESSED", "SHIPPED", "DELIVERED", "RETURNED"]

class WebsiteUser(HttpUser):
    wait_time = between(1, 5)
    # All users will be limited to 10 concurrent connections at most.
    pool_manager = PoolManager(maxsize=10, block=True)

    #@task
    def aqp_test_group_by_zipcode(self):
        global operation_name
        operation_name = "aqp-requests"
        account_name = "ACCOUNT"+str(randrange(number_accounts))
        customer_name = "CUSTOMER"+str(randrange(number_customers))
        status_code = choice(status_codes)
        # %27 encodes a single quote and %23 encodes hashes in the pk
        pk = "%23".join(["ACCOUNT", account_name, "CUSTOMER", customer_name])
        aggregation_query = "select zipcode,pk,sum(amount) as total from "+table_name+" where pk=%27"+pk+"%27 group by zipcode, pk"
        print(aggregation_query)
        response = self.client.request(method='GET',name="select zipcode,pk,sum(amount) as total from performance_test_table where pk=? group by zipcode", url="/query-aggregation/"+aggregation_query, auth=(user_name, pw))
        print(response.content)

    @task
    def aqp_test_group_by_status_code(self):
        global operation_name
        operation_name = "aqp-requests"
        account_name = "ACCOUNT"+str(randrange(number_accounts))
        customer_name = "CUSTOMER"+str(randrange(number_customers))
        status_code = choice(status_codes)
        # %27 encodes a single quote and %23 encodes hashes in the pk
        pk = "%23".join(["ACCOUNT", account_name, "CUSTOMER", customer_name])
        aggregation_query = "select status, pk, count(sk) as total from "+table_name+" where pk=%27"+pk+"%27 group by status, pk"
        print(aggregation_query)
        response = self.client.request(method='GET',name="select status, pk, count(sk) as total from performance_test_table where pk=? group by status, pk", url="/query-aggregation/"+aggregation_query, auth=(user_name, pw))
        print(response.content)